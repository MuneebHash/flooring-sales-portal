package com.flooring.salesportal.order.quote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read model for the one-per-order {@code quote_draft} row (Phase 16B/16C quotation, contract §4.1),
 * loaded on the GET {@code /quote/workspace} read. Read-only like {@link com.flooring.salesportal.order.OrderEnquiry}:
 * no setters — the write path is the focused native upsert in {@link QuoteDraftWriteRepository}, not JPA.
 *
 * <p>{@code quote_total_ex_gst} is the server-computed sum of the draft lines; {@code quote_total_inc_gst}
 * is the GST-grossed total (the value pushed into the order's cosmetic sale-price override). The
 * draft carries NO cost column — costs live on the order's product/charge lines (contract §4.2).
 */
@Entity
@Table(name = "quote_draft")
@Getter
@NoArgsConstructor
public class QuoteDraft {

    @Id
    @Column(name = "quote_draft_id")
    private Long quoteDraftId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "itemised")
    private boolean itemised;

    @Column(name = "quote_total_ex_gst")
    private BigDecimal quoteTotalExGst;

    @Column(name = "quote_total_inc_gst")
    private BigDecimal quoteTotalIncGst;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
