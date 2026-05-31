package com.flooring.salesportal.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Read model for the one-per-order {@code order_customer} row (Phase 10A GET workspace read).
 * Phase 10A does not write this table — the customer save endpoint is out of scope.
 */
@Entity
@Table(name = "order_customer")
@Getter
@NoArgsConstructor
public class OrderCustomer {

    @Id
    @Column(name = "order_customer_id")
    private Long orderCustomerId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "middle_name")
    private String middleName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "email")
    private String email;

    @Column(name = "mobile")
    private String mobile;

    @Column(name = "home_phone")
    private String homePhone;

    @Column(name = "work_phone")
    private String workPhone;

    @Column(name = "company_name")
    private String companyName;
}
