package com.flooring.salesportal.auth.dto;

/**
 * Active store projection returned by login / select-store. Phase 15C adds the store-location fields
 * (phone, email, street, suburb, state_code, postcode) — already present in V2, now mapped on the
 * {@link com.flooring.salesportal.store.Store} entity — so the frontend invoice screen (PR2) can render
 * the store header from {@code activeStore} without an extra fetch. Additive: existing keys are
 * unchanged. {@code email} may be null; the rest are NOT NULL in V2.
 */
public record StoreDto(
        Integer storeId,
        String name,
        String storeCode,
        String phone,
        String email,
        String street,
        String suburb,
        String stateCode,
        String postcode
) {
}
