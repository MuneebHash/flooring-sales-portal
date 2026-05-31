package com.flooring.salesportal.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderCustomerRepository extends JpaRepository<OrderCustomer, Long> {

    // One customer row per order (uq_order_customer_order). Empty when none saved yet.
    Optional<OrderCustomer> findByOrderId(Long orderId);
}
