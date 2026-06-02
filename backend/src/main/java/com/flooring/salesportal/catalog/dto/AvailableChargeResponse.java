package com.flooring.salesportal.catalog.dto;

import java.math.BigDecimal;

/**
 * One row of {@code GET /orders/{orderId}/available-charges} (Chunk 3 D.2). Sourced directly from
 * {@code store_charge}, scoped to the session store and filtered to the order's flooring type.
 * Cost is NEVER exposed (conventions §10 / Chunk 3 F.2). {@code store_charge} has no pricing unit
 * and no stock columns — those are product-only, so they are intentionally absent here.
 */
public record AvailableChargeResponse(
        long chargeId,
        String code,
        String name,
        String flooringType,
        BigDecimal price
) {
}
