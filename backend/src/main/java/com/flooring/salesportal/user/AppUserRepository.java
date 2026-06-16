package com.flooring.salesportal.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByBusinessIdAndSalespersonCode(Long businessId, String salespersonCode);

    // Phase 15C: resolve an order's salesperson tenant-scoped (the order's user_id + the session
    // business) so the invoice PDF cannot render a name from another tenant even if data were crossed.
    Optional<AppUser> findByUserIdAndBusinessId(Long userId, Long businessId);
}
