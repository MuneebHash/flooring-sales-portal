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
import java.time.format.DateTimeFormatter;
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
            BigDecimal balanceDue) {
    }
}
