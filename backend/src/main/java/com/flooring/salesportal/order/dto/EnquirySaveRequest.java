package com.flooring.salesportal.order.dto;

/**
 * Request body for {@code PUT /orders/{orderId}/enquiry}. Full-replace upsert of the one
 * {@code order_enquiry} row (Phase 15F). Global snake_case Jackson maps the JSON fields to these
 * record components; like {@code CustomerSaveRequest} there are no id fields — {@code order_id} comes
 * from the path, and any {@code order_id}/{@code business_id}/{@code store_id} sent in the body is an
 * unknown property Jackson ignores (no reject-scan; scoping is path + session only).
 *
 * <p>Every boolean is the boxed {@link Boolean} so an omitted JSON field deserializes to {@code null}.
 * {@code OrderService} then coerces {@code null} to {@code false} for the NOT NULL product/subfloor
 * flags, but PRESERVES {@code null} for the four tri-state fields ({@code fully_installed},
 * {@code uplift}, {@code furniture}, {@code stairs}) — null there means "unanswered". An omitted text
 * field and an explicit {@code null} both bind to {@code null} (the intended replace semantics);
 * blank-after-trim is normalised to {@code null} in {@code OrderService}. {@code leadType} must be
 * exactly {@code FLOOR} / {@code PHONE} / {@code INTERNET} or null/blank — any other value is a
 * 400 VALIDATION_FAILED on field {@code lead_type} (validated in {@code OrderService}, not coerced).
 */
public record EnquirySaveRequest(
        String leadType,
        Boolean carpet,
        Boolean hardFloor,
        String productFitTiming,
        String productUsageContext,
        String roomsToCover,
        String texturedOrPlain,
        String lightOrDarkTones,
        String coloursInterested,
        String currentFlooringAndReason,
        String productsDiscussed,
        Boolean fullyInstalled,
        Boolean uplift,
        Boolean furniture,
        Boolean stairs,
        Boolean subfloorConcrete,
        Boolean subfloorTimber,
        Boolean subfloorTile,
        String briefSummary
) {
}
