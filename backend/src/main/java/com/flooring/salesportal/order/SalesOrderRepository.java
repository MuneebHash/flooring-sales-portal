package com.flooring.salesportal.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

    /**
     * Resource-level tenant + store scoping for {@code GET /orders/{orderId}}.
     * An order outside the session's {@code (business_id, store_id)} returns empty, which the
     * service maps to 404 {@code ORDER_NOT_FOUND} (never confirms cross-tenant existence).
     */
    Optional<SalesOrder> findByOrderIdAndBusinessIdAndStoreId(Long orderId, Long businessId, Integer storeId);
}
