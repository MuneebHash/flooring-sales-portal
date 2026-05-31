package com.flooring.salesportal.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderAddressRepository extends JpaRepository<OrderAddress, Long> {

    // At most one INSTALLATION and one BILLING row per order (uq_order_address_order_type).
    List<OrderAddress> findByOrderId(Long orderId);
}
