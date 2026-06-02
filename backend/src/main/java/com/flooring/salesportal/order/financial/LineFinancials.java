package com.flooring.salesportal.order.financial;

import java.math.BigDecimal;

/**
 * Aggregate line totals for one line table scoped to an order: {@code subtotal} = sum of
 * {@code line_total}, {@code cost} = sum of {@code line_cost}. Both are returned as {@code 0.00}
 * (never null) when the order has no lines of that kind, so the {@link OrderFinancialCalculator}
 * can treat product and charge contributions uniformly. {@code cost} stays backend-only — it feeds
 * the aggregate {@code total_cost} but is never exposed per line.
 */
public record LineFinancials(BigDecimal subtotal, BigDecimal cost) {
}
