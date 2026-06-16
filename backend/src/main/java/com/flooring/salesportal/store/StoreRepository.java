package com.flooring.salesportal.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Integer> {

    // Phase 15C: load a store tenant-scoped (store id + the session business) for the invoice PDF.
    // Business-scoped so the PDF assembler cannot read a store from another tenant.
    Optional<Store> findByStoreIdAndBusinessId(Integer storeId, Long businessId);

    @Query(value = """
            SELECT s.*
            FROM store s
            INNER JOIN user_store_access usa
                    ON usa.store_id = s.store_id
            WHERE s.is_active = TRUE
              AND s.business_id = :businessId
              AND usa.business_id = :businessId
              AND usa.user_id = :userId
            ORDER BY s.store_id ASC
            """, nativeQuery = true)
    List<Store> findAccessibleActiveStores(@Param("businessId") Long businessId,
                                           @Param("userId") Long userId);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM store
                WHERE store_id = :storeId
                  AND business_id = :businessId
                  AND is_active = TRUE
            )
            """, nativeQuery = true)
    boolean existsActiveStoreInBusiness(@Param("businessId") Long businessId,
                                        @Param("storeId") Integer storeId);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM user_store_access
                WHERE business_id = :businessId
                  AND user_id = :userId
                  AND store_id = :storeId
            )
            """, nativeQuery = true)
    boolean userHasStoreAccess(@Param("businessId") Long businessId,
                               @Param("userId") Long userId,
                               @Param("storeId") Integer storeId);
}
