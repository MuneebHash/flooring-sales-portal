package com.flooring.salesportal.order;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * Renders the current-invoice snapshot to a PDF byte array (Phase 12 Chunk 4 D.1). A Thymeleaf
 * template ({@code templates/invoice.html}, well-formed XHTML) is processed to an HTML string, then
 * converted to PDF by openhtmltopdf-pdfbox. The byte array is handed to {@code FileStorageService}
 * for storage and to {@code stored_file} (file_size = byte length).
 *
 * <p>A dedicated standalone {@link TemplateEngine} with a {@link ClassLoaderTemplateResolver} is used
 * (rather than the auto-configured Spring MVC view engine) so invoice rendering is independent of web
 * view resolution. The template only references pre-formatted string/number variables (set below), so
 * there is no record-accessor / property-resolution ambiguity. All user text (e.g. details of sale)
 * is escaped by Thymeleaf {@code th:text}, keeping the output XML-well-formed for openhtmltopdf.
 */
@Component
public class InvoicePdfGenerator {

    // Locale pinned so the customer-facing PDF renders amounts/dates consistently regardless of the
    // JVM default locale (e.g. a comma decimal separator under de_DE would otherwise corrupt amounts).
    private static final Locale DISPLAY_LOCALE = Locale.ENGLISH;
    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", DISPLAY_LOCALE);
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", DISPLAY_LOCALE);

    private final TemplateEngine templateEngine;

    public InvoicePdfGenerator() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        this.templateEngine = engine;
    }

    /** Render the invoice model to PDF bytes. Throws {@link UncheckedIOException} on a render failure. */
    public byte[] render(InvoicePdfModel model) {
        Context context = new Context();
        context.setVariable("businessName", model.businessName());
        context.setVariable("orderNumber", model.orderNumber());
        context.setVariable("versionLabel", "v" + model.versionNumber());
        context.setVariable("invoiceDate", DISPLAY_DATE.format(model.invoiceDate()));
        context.setVariable("dueDate", model.dueDate() == null ? "—" : DISPLAY_DATE.format(model.dueDate()));
        context.setVariable("customerName", model.customerName());
        context.setVariable("billingLine1", model.billingLine1());
        context.setVariable("billingLine2", model.billingLine2());
        context.setVariable("detailsOfSale", model.detailsOfSale());
        context.setVariable("salePriceIncGst", money(model.salePriceIncGst()));
        context.setVariable("totalPaid", money(model.totalPaid()));
        context.setVariable("balanceDue", money(model.balanceDue()));

        // Phase 13 §8 acceptance block: blank signature area before acceptance; after acceptance the
        // signature image + accepted name + accepted timestamp render. The signature is embedded as a
        // base64 data URI — withHtmlContent(html, null) below has a NULL baseUri, so relative/classpath
        // image src values would not resolve, while data URIs need no resolution.
        boolean accepted = model.acceptedAt() != null;
        context.setVariable("accepted", accepted);
        context.setVariable("acceptedCustomerName", model.acceptedCustomerName());
        context.setVariable("acceptedAtDisplay",
                model.acceptedAt() == null ? "" : DISPLAY_DATE_TIME.format(model.acceptedAt()));
        context.setVariable("signatureDataUri", model.signaturePng() == null
                ? null
                : "data:image/png;base64," + Base64.getEncoder().encodeToString(model.signaturePng()));

        String html = templateEngine.process("invoice", context);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate invoice PDF", e);
        }
    }

    private static String money(BigDecimal value) {
        BigDecimal scaled = (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
        return "$" + String.format(DISPLAY_LOCALE, "%,.2f", scaled);
    }

    /**
     * Flat, pre-resolved data the invoice template needs. Built by the service from the order /
     * customer / billing address / live financial summary at create time, then frozen onto the PDF.
     *
     * <p>The three acceptance fields (Phase 13 §8) are nullable: Create / Rewrite always pass nulls
     * (an unaccepted version renders the blank signature area), the Accept flow (D.8) passes the
     * freshly captured values, and the payment path passes nulls until the 13C carry-forward branch.
     * {@code signaturePng} is the raw PNG bytes; the generator base64-encodes them into a data URI.
     */
    public record InvoicePdfModel(
            String businessName,
            String orderNumber,
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
