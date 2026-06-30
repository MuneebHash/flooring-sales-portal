package com.flooring.salesportal.order.quote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Read model for a {@code quote_draft_line} row (Phase 16B/16C quotation, contract §4.2), loaded
 * ordered by {@code sort_order} for the GET {@code /quote/workspace} read. Read-only like
 * {@link QuoteDraft}; the write path is the full-replace native write in {@link QuoteDraftWriteRepository}.
 *
 * <p>{@code lineType} is {@code ITEM} or {@code ADJUSTMENT}. For an ITEM line {@code quantity} and
 * {@code unitPriceExGst} are present and {@code lineTotalExGst = quantity * unitPriceExGst}; for an
 * ADJUSTMENT line they are NULL and {@code lineTotalExGst} is a signed value (may be negative).
 * There is deliberately NO cost field (customer-facing presentation only, contract §4.2).
 */
@Entity
@Table(name = "quote_draft_line")
@Getter
@NoArgsConstructor
public class QuoteDraftLine {

    @Id
    @Column(name = "quote_draft_line_id")
    private Long quoteDraftLineId;

    @Column(name = "quote_draft_id")
    private Long quoteDraftId;

    @Column(name = "line_type")
    private String lineType;

    @Column(name = "description")
    private String description;

    @Column(name = "quantity")
    private BigDecimal quantity;

    @Column(name = "unit_price_ex_gst")
    private BigDecimal unitPriceExGst;

    @Column(name = "line_total_ex_gst")
    private BigDecimal lineTotalExGst;

    @Column(name = "sort_order")
    private int sortOrder;
}
