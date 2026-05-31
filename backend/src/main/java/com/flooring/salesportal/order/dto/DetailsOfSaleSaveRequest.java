package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request body for {@code PUT /orders/{orderId}/details-of-sale} (Phase 10D). Full-replace of the
 * non-line, non-payment "details of sale" fields stored on {@code sales_order}.
 *
 * <p>Field typing is deliberate so the MALFORMED_JSON vs VALIDATION_FAILED boundary is exact —
 * only genuinely unreadable JSON is MALFORMED_JSON; valid JSON with wrong/missing/null values is
 * VALIDATION_FAILED:
 * <ul>
 *   <li>{@code supplyOnly} is a {@link JsonNode}, not a {@code boolean}/{@code Boolean}. Jackson
 *       binds any JSON value (including {@code "abc"}, a number, or {@code null}) into the node
 *       without throwing, so missing ({@code null} component), explicit JSON null
 *       ({@link JsonNode#isNull()}), and non-boolean values all reach {@code OrderService}
 *       validation as 400 VALIDATION_FAILED on field {@code supply_only} — never a parse error.</li>
 *   <li>{@code proposedLayDate} is a raw {@link String}, not a {@code LocalDate}, so an invalid /
 *       impossible date is validated to 400 VALIDATION_FAILED, not a Jackson parse error.</li>
 *   <li>{@code layDateStatus} is a raw {@link String}, not an enum, so an invalid value is
 *       validated to 400 VALIDATION_FAILED, not a Jackson parse error.</li>
 * </ul>
 *
 * <p>Global snake_case Jackson maps the JSON keys to these record components; an unknown key is an
 * unknown property and is ignored under the project's existing Jackson config (unchanged here).
 * Presence, type, trim/blank, date, enum, and pair-rule validation all happen in
 * {@code OrderService}.
 */
public record DetailsOfSaleSaveRequest(
        JsonNode supplyOnly,
        String planNumbers,
        String proposedLayDate,
        String layDateStatus,
        String detailsOfSale
) {
}
