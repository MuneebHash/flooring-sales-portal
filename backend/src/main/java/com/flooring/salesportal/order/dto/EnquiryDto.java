package com.flooring.salesportal.order.dto;

/**
 * Nested {@code enquiry} object on the GET workspace read and the {@code data.enquiry} of the
 * enquiry save response (Phase 15F). All 19 {@code order_enquiry} data columns except IDs and
 * timestamps. {@code null} for the whole object on the workspace read when no enquiry row exists.
 *
 * <p>The four tri-state fields are boxed {@link Boolean} (Yes / No / unanswered): {@code true},
 * {@code false}, or {@code null} = unanswered. They are serialized even when {@code null} — no
 * {@code @JsonInclude(NON_NULL)} — so the frontend can distinguish "answered No" from "unanswered".
 * The product/subfloor flags are primitive {@code boolean} (always true/false). {@code leadType} is
 * the single-choice {@code FLOOR} / {@code PHONE} / {@code INTERNET}, or {@code null} = unanswered.
 */
public record EnquiryDto(
        String leadType,
        boolean carpet,
        boolean hardFloor,
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
        boolean subfloorConcrete,
        boolean subfloorTimber,
        boolean subfloorTile,
        String briefSummary
) {
}
