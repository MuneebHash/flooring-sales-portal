package com.flooring.salesportal.order;

import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.InvoicePdfGenerator.InvoicePdfModel;
import com.flooring.salesportal.store.Store;
import com.flooring.salesportal.store.StoreRepository;
import com.flooring.salesportal.tenant.Business;
import com.flooring.salesportal.tenant.BusinessInvoiceConfigView;
import com.flooring.salesportal.tenant.BusinessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Phase 15C — single assembly point for the invoice PDF model.
 *
 * <p>Enriches the order/customer/financial data the three invoice-PDF build sites already have
 * (D.1 Create / D.2 Rewrite, D.8 Accept, D.7 Payment-regenerate) with the tenant invoice layout data:
 * private tenant config (ABN, bank details, per-flooring-type terms, logo), store contact/address, and
 * the order-bound salesperson name. Centralising this guarantees ALL THREE flows render the same layout
 * — adding a field here covers every flow at once.
 *
 * <p>Tenant-isolation: store and salesperson are loaded scoped to the session business id; tenant
 * config is read by business id through the leak-guarded native projection. Fail-soft: a missing
 * store/salesperson/config row, an unreadable/unsupported logo, or unsafe/blank terms degrade
 * gracefully (omit the field / fall back to business name) and never break PDF generation.
 */
@Component
public class InvoicePdfModelAssembler {

    private static final Logger log = LoggerFactory.getLogger(InvoicePdfModelAssembler.class);

    private static final String FLOORING_SOFT = "SOFT";
    private static final String FLOORING_HARD = "HARD";

    // Logo bytes larger than this are never decoded (a corrupt/huge file must not OOM or stall PDF
    // generation) — fail soft to the business-name text fallback instead.
    private static final long MAX_LOGO_BYTES = 5L * 1024 * 1024;

    private final BusinessRepository businessRepository;
    private final StoreRepository storeRepository;
    private final OrderSalespersonResolver salespersonResolver;
    private final FileStorageService fileStorageService;
    private final InvoiceTermsSanitizer termsSanitizer;

    public InvoicePdfModelAssembler(BusinessRepository businessRepository,
                                    StoreRepository storeRepository,
                                    OrderSalespersonResolver salespersonResolver,
                                    FileStorageService fileStorageService,
                                    InvoiceTermsSanitizer termsSanitizer) {
        this.businessRepository = businessRepository;
        this.storeRepository = storeRepository;
        this.salespersonResolver = salespersonResolver;
        this.fileStorageService = fileStorageService;
        this.termsSanitizer = termsSanitizer;
    }

    /**
     * Build the full {@link InvoicePdfModel} from the caller's snapshot inputs + the tenant/store/
     * salesperson layout data. The caller still passes the frozen money/details/acceptance values; this
     * method only ADDS layout fields and never re-reads live sale state.
     */
    public InvoicePdfModel assemble(Inputs in) {
        Business business = in.business();
        Long businessId = business.getBusinessId();
        String flooringType = in.order().getFlooringType();

        BusinessInvoiceConfigView config = businessRepository
                .findInvoiceConfigByBusinessId(businessId)
                .orElse(null);

        Store store = storeRepository
                .findByStoreIdAndBusinessId(in.order().getStoreId(), businessId)
                .orElse(null);

        String salespersonName = salespersonResolver.resolveName(in.order().getUserId(), businessId);
        String logoDataUri = resolveLogoDataUri(business.getLogoPath());

        // Per-flooring-type terms: SOFT -> terms_soft, HARD -> terms_hard. NO fallback to the legacy
        // terms_and_conditions. Sanitize to safe, XML-well-formed HTML (null when blank/unsafe/fails).
        String rawTerms = config == null
                ? null
                : (FLOORING_SOFT.equals(flooringType) ? config.getTermsSoft() : config.getTermsHard());
        String termsHtml = termsSanitizer.sanitize(rawTerms);
        // HARD terms render on a dedicated second page; SOFT terms render inline on page 1.
        boolean termsOnSeparatePage = FLOORING_HARD.equals(flooringType);

        return new InvoicePdfModel(
                business.getName(),
                blankToNull(config == null ? null : config.getAbn()),
                logoDataUri,
                flooringTypeLabel(flooringType),
                store == null ? null : blankToNull(store.getName()),
                store == null ? null : blankToNull(store.getStreet()),
                store == null ? null : storeLocality(store),
                store == null ? null : blankToNull(store.getPhone()),
                store == null ? null : blankToNull(store.getEmail()),
                in.order().getOrderNumber(),
                in.versionNumber(),
                in.invoiceDate(),
                in.dueDate(),
                salespersonName,
                in.customerName(),
                in.billingLine1(),
                in.billingLine2(),
                in.detailsOfSale(),
                in.salePriceIncGst(),
                in.totalPaid(),
                in.balanceDue(),
                blankToNull(config == null ? null : config.getBankName()),
                blankToNull(config == null ? null : config.getBsb()),
                blankToNull(config == null ? null : config.getAccountName()),
                blankToNull(config == null ? null : config.getAccountNumber()),
                in.acceptedAt(),
                in.acceptedCustomerName(),
                in.signaturePng(),
                termsHtml,
                termsOnSeparatePage);
    }

    /**
     * Resolve {@code logo_path} to a base64 data URI (mirrors the signature embedding) for the PDF.
     * Fail-soft to {@code null} (the template falls back to business-name text) on a null/blank path, an
     * unsupported extension, a read failure, oversized bytes, bytes that are not a real PNG/JPEG, an
     * extension/content-type mismatch, or any decode failure. NEVER throws — a logo problem must not
     * 500 the invoice, including a readable-but-corrupt/mislabeled file that would otherwise blow up
     * later inside openhtmltopdf.
     */
    private String resolveLogoDataUri(String logoPath) {
        if (logoPath == null || logoPath.isBlank()) {
            return null;
        }
        String path = logoPath.trim();
        String extensionMime = logoMimeForPath(path);
        if (extensionMime == null) {
            log.warn("Invoice logo path has an unsupported type ({}) — falling back to business name", path);
            return null;
        }

        // Bounded read: the size cap is enforced BEFORE the file is fully materialised (readWithLimit
        // never buffers more than MAX_LOGO_BYTES + 1), so an oversized/huge logo cannot OOM at read time.
        // null => over the cap (or empty); a read failure (missing/unreadable) throws and is caught here.
        byte[] bytes;
        try {
            bytes = fileStorageService.readWithLimit(path, MAX_LOGO_BYTES);
        } catch (RuntimeException ex) {
            log.warn("Invoice logo unavailable for path {} — falling back to business name", path, ex);
            return null;
        }
        if (bytes == null) {
            log.warn("Invoice logo at {} is missing or exceeds the {}-byte cap — falling back to business name",
                    path, MAX_LOGO_BYTES);
            return null;
        }
        if (bytes.length == 0) {
            return null;
        }

        // Magic-byte type must be a supported image AND match the extension-derived type
        // (.png must hold real PNG bytes; .jpg/.jpeg must hold real JPEG bytes).
        String detectedMime = detectImageMime(bytes);
        if (detectedMime == null || !detectedMime.equals(extensionMime)) {
            log.warn("Invoice logo at {} is not a valid/consistent image (detected={}, extension={}) "
                    + "— falling back to business name", path, detectedMime, extensionMime);
            return null;
        }

        // Decode-validate the bytes already read. catch(Throwable): malformed input can raise
        // OutOfMemoryError or other non-RuntimeException throwables — none may propagate.
        try {
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
                log.warn("Invoice logo at {} could not be decoded as an image — falling back to business name", path);
                return null;
            }
        } catch (Throwable t) {
            log.warn("Invoice logo at {} failed to decode — falling back to business name", path, t);
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

    /** Detect a supported image type from its leading magic bytes; null for anything else. */
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

    /**
     * The frozen snapshot inputs a build site already has. The assembler adds tenant/store/salesperson/
     * logo/terms; it never re-reads live sale state. {@code business} and {@code order} carry the ids
     * (business id, store id, order user id, flooring type) used for the scoped layout lookups.
     */
    public record Inputs(
            Business business,
            SalesOrder order,
            int versionNumber,
            LocalDate invoiceDate,
            LocalDate dueDate,
            String customerName,
            String billingLine1,
            String billingLine2,
            String detailsOfSale,
            BigDecimal salePriceIncGst,
            BigDecimal totalPaid,
            BigDecimal balanceDue,
            LocalDateTime acceptedAt,
            String acceptedCustomerName,
            byte[] signaturePng) {
    }
}
