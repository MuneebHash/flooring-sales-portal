package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.order.quote.QuotePdfModel.QuotePdfLine;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 16C PR2 — renders a {@link QuotePdfModel} to PDF bytes for the on-demand quote PREVIEW. A
 * Thymeleaf template ({@code templates/quote.html}, well-formed XHTML) is processed to an HTML string,
 * then converted to PDF by openhtmltopdf-pdfbox. The bytes are streamed to the client and NEVER stored
 * (no {@code stored_file}, no {@code FileStorageService}).
 *
 * <p>PURE: {@code model -> byte[]}, with no DB access, no file IO, and no mutation — the same shape as
 * {@code InvoicePdfGenerator}. A dedicated standalone {@link TemplateEngine} with a
 * {@link ClassLoaderTemplateResolver} is used (rather than the Spring MVC view engine) so quote
 * rendering is independent of web view resolution. All user text is escaped via {@code th:text} in the
 * template (sanitized terms are injected via {@code th:utext}), keeping the output XML-well-formed for
 * openhtmltopdf.
 */
@Component
public class QuotePdfGenerator {

    // Locale pinned so the customer-facing PDF renders amounts consistently regardless of the JVM
    // default locale (e.g. a comma decimal separator under de_DE would otherwise corrupt amounts).
    private static final Locale DISPLAY_LOCALE = Locale.ENGLISH;
    private static final int MONEY_SCALE = 2;

    // Phase 16D-C: hardcoded, display-only deposit — 40% of the inc-GST total for every tenant/store
    // (no tenant config). Render-time math only: never persisted, never in any API body; rounded
    // HALF_UP to 2dp by money(). A future per-tenant percentage is explicitly out of scope.
    private static final BigDecimal DEPOSIT_RATE = new BigDecimal("0.40");

    private final TemplateEngine templateEngine;

    public QuotePdfGenerator() {
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

    /** Render the quote model to PDF bytes. Throws {@link UncheckedIOException} on a render failure. */
    public byte[] render(QuotePdfModel model) {
        Context context = new Context();

        // Header / business. logoDataUri is a base64 image (or null -> business-name text);
        // abn / flooringTypeLabel are nullable and the template hides them when null.
        context.setVariable("businessName", model.businessName());
        context.setVariable("abn", model.abn());
        context.setVariable("logoDataUri", model.logoDataUri());
        context.setVariable("flooringTypeLabel", model.flooringTypeLabel());

        // Store contact / address — each nullable; the template hides blank lines.
        context.setVariable("storeName", model.storeName());
        context.setVariable("storeAddressLine1", model.storeAddressLine1());
        context.setVariable("storeAddressLine2", model.storeAddressLine2());
        context.setVariable("storePhone", model.storePhone());
        context.setVariable("storeEmail", model.storeEmail());

        // Order / customer.
        context.setVariable("orderNumber", model.orderNumber());
        context.setVariable("salespersonName", model.salespersonName());
        context.setVariable("customerName", blankToNull(model.customerName()));
        context.setVariable("billingLine1", blankToNull(model.billingLine1()));
        context.setVariable("billingLine2", blankToNull(model.billingLine2()));
        context.setVariable("detailsOfSale", blankToNull(model.detailsOfSale()));

        // Quote body. The itemised flag selects the line table vs the single-amount presentation.
        // Lines are pre-formatted into display maps so the template never formats money/quantity.
        context.setVariable("itemised", model.itemised());
        context.setVariable("lines", toDisplayLines(model.lines()));

        // Totals: subtotal (ex GST), GST (inc - ex), total (inc GST) — server-formatted verbatim —
        // plus the display-only 40% deposit derived from the inc-GST total (Phase 16D-C).
        BigDecimal ex = scale(model.quoteTotalExGst());
        BigDecimal inc = scale(model.quoteTotalIncGst());
        context.setVariable("subtotalExGst", money(ex));
        context.setVariable("gstAmount", money(inc.subtract(ex)));
        context.setVariable("totalIncGst", money(inc));
        context.setVariable("depositAmount", money(inc.multiply(DEPOSIT_RATE)));

        // Payment methods / bank details (nullable -> hidden when absent).
        context.setVariable("bankName", model.bankName());
        context.setVariable("bsb", model.bsb());
        context.setVariable("accountName", model.accountName());
        context.setVariable("accountNumber", model.accountNumber());

        // Terms: pre-sanitized, XML-well-formed HTML (null -> no terms block/page). The template renders
        // them on a dedicated page when present (gated solely on termsHtml != null, SOFT and HARD alike).
        // Rendered with th:utext; everything else stays th:text-escaped.
        context.setVariable("termsHtml", model.termsHtml());

        String html = templateEngine.process("quote", context);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate quote PDF", e);
        }
    }

    /**
     * Pre-format each draft line into a display map (description / qty / unit / amount). ITEM lines carry
     * a formatted quantity + unit price; ADJUSTMENT lines leave qty/unit blank and show only the signed
     * amount. Used only by the itemised line table; a null/empty list yields an empty table.
     */
    private static List<Map<String, String>> toDisplayLines(List<QuotePdfLine> lines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (QuotePdfLine line : lines) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("description", line.description() == null ? "" : line.description());
            boolean item = "ITEM".equals(line.lineType());
            row.put("qty", item ? quantity(line.quantity()) : "");
            row.put("unit", item && line.unitPriceExGst() != null ? money(line.unitPriceExGst()) : "");
            row.put("amount", money(line.lineTotalExGst()));
            out.add(row);
        }
        return out;
    }

    /** Quantity without trailing zeros (e.g. 2.00 -> "2", 1.50 -> "1.5"); blank for null. */
    private static String quantity(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    /** Money for display: "$1,234.50", and "-$50.00" for negatives (signed adjustments). */
    private static String money(BigDecimal value) {
        BigDecimal scaled = scale(value);
        String sign = scaled.signum() < 0 ? "-" : "";
        return sign + "$" + String.format(DISPLAY_LOCALE, "%,.2f", scaled.abs());
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
