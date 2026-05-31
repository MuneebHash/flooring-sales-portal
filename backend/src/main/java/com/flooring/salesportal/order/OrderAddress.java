package com.flooring.salesportal.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Read model for {@code order_address} rows (Phase 10A GET workspace read).
 *
 * <p>One row per {@code (order_id, address_type)}. The {@code address_type} PostgreSQL enum is
 * mapped as {@code String} ("INSTALLATION" / "BILLING") and filtered in the service layer.
 * Phase 10A does not write this table — address save/copy endpoints are out of scope.
 */
@Entity
@Table(name = "order_address")
@Getter
@NoArgsConstructor
public class OrderAddress {

    @Id
    @Column(name = "order_address_id")
    private Long orderAddressId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "address_type")
    private String addressType;

    @Column(name = "unit_number")
    private String unitNumber;

    @Column(name = "street_number")
    private String streetNumber;

    @Column(name = "street")
    private String street;

    @Column(name = "suburb")
    private String suburb;

    @Column(name = "state_code")
    private String stateCode;

    @Column(name = "postcode")
    private String postcode;
}
