package com.flooring.salesportal.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

    /**
     * Resource-level tenant + store scoping for {@code GET /orders/{orderId}}.
     * An order outside the session's {@code (business_id, store_id)} returns empty, which the
     * service maps to 404 {@code ORDER_NOT_FOUND} (never confirms cross-tenant existence).
     */
    Optional<SalesOrder> findByOrderIdAndBusinessIdAndStoreId(Long orderId, Long businessId, Integer storeId);

    /**
     * Same scoped lookup as above but takes a pessimistic write lock ({@code SELECT ... FOR UPDATE})
     * on the order row. Used by mutation paths (e.g. {@code PUT /orders/{orderId}/customer}) so the
     * LAID check and the subsequent write see a consistent row: a concurrent PATCH-status update to
     * {@code LAID} cannot interleave between the read and the commit. Must be called inside a
     * transaction; the lock is held until that transaction commits/rolls back.
     */
    // Explicit @Query: the "ForUpdate" suffix is not a derivable property keyword, so the query
    // is written out and the pessimistic lock is applied via @Lock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM SalesOrder o "
            + "WHERE o.orderId = :orderId AND o.businessId = :businessId AND o.storeId = :storeId")
    Optional<SalesOrder> findByOrderIdAndBusinessIdAndStoreIdForUpdate(
            @Param("orderId") Long orderId,
            @Param("businessId") Long businessId,
            @Param("storeId") Integer storeId);
}
