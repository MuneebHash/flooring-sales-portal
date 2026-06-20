package com.flooring.salesportal.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderEnquiryRepository extends JpaRepository<OrderEnquiry, Long> {

    // One enquiry row per order (uq_order_enquiry_order). Empty when none saved yet.
    Optional<OrderEnquiry> findByOrderId(Long orderId);
}
