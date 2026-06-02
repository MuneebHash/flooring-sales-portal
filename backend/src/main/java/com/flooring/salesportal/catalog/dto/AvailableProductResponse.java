package com.flooring.salesportal.catalog.dto;

import java.math.BigDecimal;

/**
 * One row of {@code GET /orders/{orderId}/available-products} (Chunk 3 D.1). Every field is
 * sourced directly from {@code store_product}, scoped to the session store and filtered to the
 * order's flooring type. Cost is NEVER exposed on catalog search (conventions §10 / Chunk 3 F.2).
 *
 * <p>{@code stockQuantity} and {@code stockUnit} are nullable and paired by
 * {@code chk_store_product_stock_pair}; when absent they are serialized as {@code null}
 * (conventions §3 null-vs-absent). Money / factor columns are {@code BigDecimal} so the
 * {@code DECIMAL} scale is preserved (e.g. {@code 45.00}, {@code 3.66}, {@code 4.00}). Jackson's
 * global snake_case strategy renders the field names (e.g. {@code sqmPerLm} → {@code sqm_per_lm}).
 */
public record AvailableProductResponse(
        long productId,
        String code,
        String name,
        String flooringType,
        String pricingUnit,
        BigDecimal price,
        BigDecimal sqmPerLm,
        BigDecimal stockQuantity,
        String stockUnit
) {
}
