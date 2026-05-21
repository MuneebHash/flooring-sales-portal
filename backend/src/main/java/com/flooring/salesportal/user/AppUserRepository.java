package com.flooring.salesportal.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByBusinessIdAndSalespersonCode(Long businessId, String salespersonCode);
}
