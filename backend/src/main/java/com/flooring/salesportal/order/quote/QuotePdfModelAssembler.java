package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.InvoiceTermsSanitizer;
import com.flooring.salesportal.order.OrderAddress;
import com.flooring.salesportal.order.OrderAddressRepository;
import com.flooring.salesportal.order.OrderCustomer;
import com.flooring.salesportal.order.OrderCustomerRepository;
import com.flooring.salesportal.order.OrderSalespersonResolver;
import com.flooring.salesportal.order.SalesOrder;
import com.flooring.salesportal.order.quote.QuotePdfModel.QuotePdfLine;
import com.flooring.salesportal.store.Store;
import com.flooring.salesportal.store.StoreRepository;
import com.flooring.salesportal.tenant.Business;
import com.flooring.salesportal.tenant.BusinessInvoiceConfigView;
import com.flooring.salesportal.tenant.BusinessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Phase 16C PR2 — single assembly point for the quote PDF model: the on-demand DRAFT preview
 * ({@link #assemble}) and, since Phase 16E-A, the ISSUED snapshot render
 * ({@link #assembleIssued}). The quote analogue of {@code InvoicePdfModelAssembler}: it enriches
 * the quote body with the tenant invoice layout data (private config — ABN, bank details,
 * per-flooring-type terms, logo), the store contact/address, the order-bound salesperson, and the
 * customer + billing address.
 *
 * <p><b>Read-only.</b> Every lookup here is a SELECT; this assembler NEVER writes a row, never calls
 * {@link FileStorageService#store} (only the bounded read for the logo), and never advances any
 * lifecycle. The caller ({@code QuoteService.previewPdf}) has already resolved the order scoped to the
 * session {@code (business_id, store_id)}; this assembler loads the remaining presentation context by
 * the same business id, so it cannot widen scope.
 *
 * <p><b>Fail-soft.</b> A missing store / salesperson / config row, an unreadable/unsupported logo, or
 * blank/unsafe terms degrade gracefully (omit the field / fall back to the business name) and never
 * break PDF generation — same posture as the invoice assembler. The helpers (customer name / billing
 * lines / logo data URI / store locality / terms selection) are duplicated from the invoice assembler
 * per the locked "duplicate, don't extract" decision for the quote feature, so the invoice PDF code is
 * left untouched.
 */
@Component
public class QuotePdfModelAssembler {

    private static final Logger log = LoggerFactory.getLogger(QuotePdfModelAssembler.class);

    private static final String FLOORING_SOFT = "SOFT";
    private static final String FLOORING_HARD = "HARD";
    private static final String ADDRESS_BILLING = "BILLING";

    // Logo bytes larger than this are never decoded (a corrupt/huge file must not OOM or stall PDF
    // generation) — fail soft to the business-name text fallback instead.
    private static final long MAX_LOGO_BYTES = 5L * 1024 * 1024;

    private final BusinessRepository businessRepository;
    private final StoreRepository storeRepository;
    private final OrderSalespersonResolver salespersonResolver;
    private final FileStorageService fileStorageService;
    private final InvoiceTermsSanitizer termsSanitizer;
    private final OrderCustomerRepository orderCustomerRepository;
    private final OrderAddressRepository orderAddressRepository;

    public QuotePdfModelAssembler(BusinessRepository businessRepository,
                                  StoreRepository storeRepository,
                                  OrderSalespersonResolver salespersonResolver,
                                  FileStorageService fileStorageService,
                                  InvoiceTermsSanitizer termsSanitizer,
                                  OrderCustomerRepository orderCustomerRepository,
                                  OrderAddressRepository orderAddressRepository) {
        this.businessRepository = businessRepository;
        this.storeRepository = storeRepository;
        this.salespersonResolver = salespersonResolver;
        this.fileStorageService = fileStorageService;
        this.termsSanitizer = termsSanitizer;
        this.orderCustomerRepository = orderCustomerRepository;
        this.orderAddressRepository = orderAddressRepository;
    }

    /**
     * Build the {@link QuotePdfModel} from the scoped order + persisted draft + ordered draft lines
     * (the on-demand PREVIEW path — unchanged behaviour). The caller has already resolved
     * {@code business}/{@code order} scoped to the session; this method only ADDS read-only layout
     * context. {@code lines} is the draft line set ordered by sort_order.
     */
    public QuotePdfModel assemble(Business business, SalesOrder order, long orderId,
                                  QuoteDraft draft, List<QuoteDraftLine> lines) {
        BusinessInvoiceConfigView config = businessRepository
                .findInvoiceConfigByBusinessId(business.getBusinessId())
                .orElse(null);

        // Per-flooring-type terms: SOFT -> terms_soft, HARD -> terms_hard. NO fallback to legacy terms.
        // Sanitize to safe, XML-well-formed HTML (null when blank/unsafe/fails). The DRAFT preview uses
        // the LIVE current terms (the frozen terms_snapshot only exists on an ISSUED quote_version).
        String flooringType = order.getFlooringType();
        String rawTerms = config == null
                ? null
                : (FLOORING_SOFT.equals(flooringType) ? config.getTermsSoft() : config.getTermsHard());
        String termsHtml = termsSanitizer.sanitize(rawTerms);

        return buildModel(business, order, orderId, config,
                draft.isItemised(), toPdfLines(lines),
                draft.getQuoteTotalExGst(), draft.getQuoteTotalIncGst(),
                flooringType, blankToNull(order.getDetailsOfSale()), termsHtml);
    }

    /**
     * Phase 16E-A — build the {@link QuotePdfModel} for the ISSUED quote PDF from the frozen
     * {@link QuoteIssueSnapshot}. The quote BODY (itemised flag, lines — already MODE-SCOPED, so a
     * non-itemised issue renders the single-amount presentation with no dormant rows — totals,
     * flooring-type label, details-of-sale text, and the once-sanitized {@code terms_snapshot}
     * HTML) comes EXCLUSIVELY from the snapshot: this method never reads the live draft, the live
     * draft lines, or the live tenant terms, so later edits can never leak into the stored issued
     * document. Presentation context (store / salesperson / customer / billing / logo / bank /
     * ABN) is read live AT ISSUE TIME — the stored PDF bytes are the frozen artifact for that data
     * (resends re-deliver the stored bytes verbatim; they never re-render).
     */
    public QuotePdfModel assembleIssued(Business business, SalesOrder order, long orderId,
                                        QuoteIssueSnapshot snapshot) {
        BusinessInvoiceConfigView config = businessRepository
                .findInvoiceConfigByBusinessId(business.getBusinessId())
                .orElse(null);

        return buildModel(business, order, orderId, config,
                snapshot.itemised(), snapshotToPdfLines(snapshot.lines()),
                snapshot.quoteTotalExGst(), snapshot.quoteTotalIncGst(),
                snapshot.flooringType(), blankToNull(snapshot.detailsOfSale()), snapshot.termsHtml());
    }

    /**
     * Shared model construction: live presentation context (config bank/ABN, store, salesperson,
     * logo, customer, billing address) around a caller-supplied quote body. The preview path feeds
     * the live draft + live sanitized terms; the issued path feeds the frozen snapshot.
     */
    private QuotePdfModel buildModel(Business business, SalesOrder order, long orderId,
                                     BusinessInvoiceConfigView config,
                                     boolean itemised, List<QuotePdfLine> pdfLines,
                                     java.math.BigDecimal quoteTotalExGst,
                                     java.math.BigDecimal quoteTotalIncGst,
                                     String flooringType, String detailsOfSale, String termsHtml) {
        Long businessId = business.getBusinessId();

        Store store = storeRepository
                .findByStoreIdAndBusinessId(order.getStoreId(), businessId)
                .orElse(null);

        String salespersonName = salespersonResolver.resolveName(order.getUserId(), businessId);
        String logoDataUri = resolveLogoDataUri(business.getLogoPath());

        OrderCustomer customer = orderCustomerRepository.findByOrderId(orderId).orElse(null);
        List<OrderAddress> addresses = orderAddressRepository.findByOrderId(orderId);

        return new QuotePdfModel(
                business.getName(),
                blankToNull(config == null ? null : config.getAbn()),
                logoDataUri,
                flooringTypeLabel(flooringType),
                store == null ? null : blankToNull(store.getName()),
                store == null ? null : blankToNull(store.getStreet()),
                store == null ? null : storeLocality(store),
                store == null ? null : blankToNull(store.getPhone()),
                store == null ? null : blankToNull(store.getEmail()),
                order.getOrderNumber(),
                salespersonName,
                customer == null ? null : blankToNull(customerName(customer)),
                blankToNull(billingLine1(addresses)),
                blankToNull(billingLine2(addresses)),
                detailsOfSale,
                itemised,
                pdfLines,
                quoteTotalExGst,
                quoteTotalIncGst,
                blankToNull(config == null ? null : config.getBankName()),
                blankToNull(config == null ? null : config.getBsb()),
                blankToNull(config == null ? null : config.getAccountName()),
                blankToNull(config == null ? null : config.getAccountNumber()),
                termsHtml);
    }

    private static List<QuotePdfLine> snapshotToPdfLines(List<QuoteIssueSnapshot.Line> lines) {
        List<QuotePdfLine> out = new ArrayList<>(lines == null ? 0 : lines.size());
        if (lines == null) {
            return out;
        }
        for (QuoteIssueSnapshot.Line line : lines) {
            out.add(new QuotePdfLine(
                    line.lineType(),
                    line.description(),
                    line.quantity(),
                    line.unitPriceExGst(),
                    line.lineTotalExGst()));
        }
        return out;
    }

    private static List<QuotePdfLine> toPdfLines(List<QuoteDraftLine> lines) {
        List<QuotePdfLine> out = new ArrayList<>(lines == null ? 0 : lines.size());
        if (lines == null) {
            return out;
        }
        for (QuoteDraftLine line : lines) {
            out.add(new QuotePdfLine(
                    line.getLineType(),
                    line.getDescription(),
                    line.getQuantity(),
                    line.getUnitPriceExGst(),
                    line.getLineTotalExGst()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Customer name / billing address (duplicated from OrderInvoiceService helpers)
    // ------------------------------------------------------------------

    private static String customerName(OrderCustomer customer) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, customer.getFirstName());
        appendIfPresent(sb, customer.getMiddleName());
        appendIfPresent(sb, customer.getLastName());
        return sb.toString();
    }

    private static String billingLine1(List<OrderAddress> addresses) {
        OrderAddress billing = billing(addresses);
        if (billing == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (billing.getUnitNumber() != null && !billing.getUnitNumber().isBlank()) {
            sb.append(billing.getUnitNumber().trim()).append('/');
        }
        sb.append(billing.getStreetNumber()).append(' ').append(billing.getStreet());
        return sb.toString();
    }

    private static String billingLine2(List<OrderAddress> addresses) {
        OrderAddress billing = billing(addresses);
        if (billing == null) {
            return "";
        }
        return billing.getSuburb() + " " + billing.getStateCode() + " " + billing.getPostcode();
    }

    private static OrderAddress billing(List<OrderAddress> addresses) {
        if (addresses == null) {
            return null;
        }
        return addresses.stream()
                .filter(a -> ADDRESS_BILLING.equals(a.getAddressType()))
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // Logo data URI (duplicated from InvoicePdfModelAssembler — fail-soft, never throws)
    // ------------------------------------------------------------------

    /**
     * Resolve {@code logo_path} to a base64 data URI for the PDF. Fail-soft to {@code null} (the template
     * falls back to business-name text) on a null/blank path, an unsupported extension, a read failure,
     * oversized bytes, bytes that are not a real PNG/JPEG, an extension/content-type mismatch, or any
     * decode failure. NEVER throws — a logo problem must not 500 the preview.
     */
    private String resolveLogoDataUri(String logoPath) {
        if (logoPath == null || logoPath.isBlank()) {
            return null;
        }
        String path = logoPath.trim();
        String extensionMime = logoMimeForPath(path);
        if (extensionMime == null) {
            log.warn("Quote logo path has an unsupported type ({}) — falling back to business name", path);
            return null;
        }

        byte[] bytes;
        try {
            bytes = fileStorageService.readWithLimit(path, MAX_LOGO_BYTES);
        } catch (RuntimeException ex) {
            log.warn("Quote logo unavailable for path {} — falling back to business name", path, ex);
            return null;
        }
        if (bytes == null) {
            log.warn("Quote logo at {} is missing or exceeds the {}-byte cap — falling back to business name",
                    path, MAX_LOGO_BYTES);
            return null;
        }
        if (bytes.length == 0) {
            return null;
        }

        String detectedMime = detectImageMime(bytes);
        if (detectedMime == null || !detectedMime.equals(extensionMime)) {
            log.warn("Quote logo at {} is not a valid/consistent image (detected={}, extension={}) "
                    + "— falling back to business name", path, detectedMime, extensionMime);
            return null;
        }

        try {
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
                log.warn("Quote logo at {} could not be decoded as an image — falling back to business name", path);
                return null;
            }
        } catch (Throwable t) {
            log.warn("Quote logo at {} failed to decode — falling back to business name", path, t);
            return null;
        }

        return "data:" + detectedMime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String logoMimeForPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return null;
    }

    private static String detectImageMime(byte[] bytes) {
        if (isPng(bytes)) {
            return "image/png";
        }
        if (isJpeg(bytes)) {
            return "image/jpeg";
        }
        return null;
    }

    // PNG signature: 89 50 4E 47 0D 0A 1A 0A.
    private static boolean isPng(byte[] b) {
        return b.length >= 8
                && (b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50 && (b[2] & 0xFF) == 0x4E && (b[3] & 0xFF) == 0x47
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A;
    }

    // JPEG signature: FF D8 FF.
    private static boolean isJpeg(byte[] b) {
        return b.length >= 3
                && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    // ------------------------------------------------------------------
    // Small layout helpers (duplicated from InvoicePdfModelAssembler)
    // ------------------------------------------------------------------

    private static String flooringTypeLabel(String flooringType) {
        if (FLOORING_SOFT.equals(flooringType)) {
            return "Soft Flooring";
        }
        if (FLOORING_HARD.equals(flooringType)) {
            return "Hard Flooring";
        }
        return null;
    }

    private static String storeLocality(Store store) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, store.getSuburb());
        appendIfPresent(sb, store.getStateCode());
        appendIfPresent(sb, store.getPostcode());
        return blankToNull(sb.toString());
    }

    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(value.trim());
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
