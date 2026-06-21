package com.flooring.salesportal.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Read model for the one-per-order {@code order_enquiry} row (Phase 15F lead enquiry form),
 * embedded as the nullable {@code enquiry} object on the GET workspace read. Read-only like
 * {@link OrderCustomer}: no generated id, no setters, timestamps unmapped — the write path is the
 * focused native upsert in {@link OrderEnquiryWriteRepository}, not JPA.
 *
 * <p>The NOT NULL product/subfloor flags are primitive {@code boolean}. The four Yes/No/unanswered
 * fields ({@code fully_installed}, {@code uplift}, {@code furniture}, {@code stairs}) are boxed
 * {@link Boolean} so the nullable "unanswered" state survives end-to-end and is never coerced to
 * {@code false}.
 */
@Entity
@Table(name = "order_enquiry")
@Getter
@NoArgsConstructor
public class OrderEnquiry {

    @Id
    @Column(name = "order_enquiry_id")
    private Long orderEnquiryId;

    @Column(name = "order_id")
    private Long orderId;

    // How the lead came in: FLOOR / PHONE / INTERNET, or null = unanswered.
    @Column(name = "lead_type")
    private String leadType;

    @Column(name = "carpet")
    private boolean carpet;

    @Column(name = "hard_floor")
    private boolean hardFloor;

    @Column(name = "product_fit_timing")
    private String productFitTiming;

    @Column(name = "product_usage_context")
    private String productUsageContext;

    @Column(name = "rooms_to_cover")
    private String roomsToCover;

    @Column(name = "textured_or_plain")
    private String texturedOrPlain;

    @Column(name = "light_or_dark_tones")
    private String lightOrDarkTones;

    @Column(name = "colours_interested")
    private String coloursInterested;

    @Column(name = "current_flooring_and_reason")
    private String currentFlooringAndReason;

    @Column(name = "products_discussed")
    private String productsDiscussed;

    // Tri-state Yes / No / unanswered — boxed Boolean, nullable; null = unanswered.
    @Column(name = "fully_installed")
    private Boolean fullyInstalled;

    @Column(name = "uplift")
    private Boolean uplift;

    @Column(name = "furniture")
    private Boolean furniture;

    @Column(name = "stairs")
    private Boolean stairs;

    @Column(name = "subfloor_concrete")
    private boolean subfloorConcrete;

    @Column(name = "subfloor_timber")
    private boolean subfloorTimber;

    @Column(name = "subfloor_tile")
    private boolean subfloorTile;

    @Column(name = "brief_summary")
    private String briefSummary;
}
