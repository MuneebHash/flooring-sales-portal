package com.flooring.salesportal.order.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request body for {@code PUT /orders/{orderId}/details-of-sale} (Phase 10D). Full-replace of the
 * non-line, non-payment "details of sale" fields stored on {@code sales_order}.
 *
 * <p>Every client-controlled field is typed as {@link JsonNode}, not a concrete Java type, so the
 * MALFORMED_JSON vs VALIDATION_FAILED boundary is exact — only genuinely unreadable JSON is
 * MALFORMED_JSON; ANY valid JSON value (including a wrong type such as an object or array where a
 * scalar is expected, or {@code null}) binds into the node without Jackson throwing, and reaches
 * {@code OrderService} validation as 400 VALIDATION_FAILED on the relevant field:
 * <ul>
 *   <li>{@code supplyOnly} — a missing component ({@code null}), explicit JSON null
 *       ({@link JsonNode#isNull()}), and any non-boolean value (string/number/object/array) are all
 *       400 VALIDATION_FAILED on {@code supply_only}.</li>
 *   <li>{@code proposedLayDate} — a non-string node (object/array/number/boolean) or an
 *       invalid/impossible date string is 400 VALIDATION_FAILED on {@code proposed_lay_date}, never
 *       a Jackson parse error.</li>
 *   <li>{@code layDateStatus} — a non-string node or an out-of-enum string is 400 VALIDATION_FAILED
 *       on {@code lay_date_status}, never a Jackson parse error.</li>
 *   <li>{@code planNumbers} / {@code detailsOfSale} — a non-string node or a blank string is 400
 *       VALIDATION_FAILED on the relevant field.</li>
 * </ul>
 *
 * <p>If these were typed as {@code String} (or {@code LocalDate}/enum), valid JSON like
 * {@code {"proposed_lay_date": {}}} would make Jackson fail while constructing the record —
 * {@code parseBodyAfterGates} would then surface it as MALFORMED_JSON, which is wrong for this
 * endpoint. Typing as {@link JsonNode} keeps all field-type errors inside
 * {@code validateAndNormalizeDetails}.
 *
 * <p>Global snake_case Jackson maps the JSON keys to these record components; an unknown key is an
 * unknown property and is ignored under the project's existing Jackson config (unchanged here).
 * Presence, type, trim/blank, date, enum, and pair-rule validation all happen in
 * {@code OrderService}.
 */
public record DetailsOfSaleSaveRequest(
        JsonNode supplyOnly,
        JsonNode planNumbers,
        JsonNode proposedLayDate,
        JsonNode layDateStatus,
        JsonNode detailsOfSale
) {
}
