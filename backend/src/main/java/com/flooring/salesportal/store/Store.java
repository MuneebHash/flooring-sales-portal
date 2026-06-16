package com.flooring.salesportal.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "store")
@Getter
@Setter
@NoArgsConstructor
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_id")
    private Integer storeId;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "store_code", nullable = false)
    private String storeCode;

    // V2 store-location columns (Phase 15C): already present in the schema, now mapped so the
    // select-store response and the invoice PDF can render store contact/address details. `email`
    // is the only nullable column; the rest are NOT NULL in V2.
    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "street", nullable = false)
    private String street;

    @Column(name = "suburb", nullable = false)
    private String suburb;

    @Column(name = "state_code", nullable = false)
    private String stateCode;

    @Column(name = "postcode", nullable = false)
    private String postcode;

    @Column(name = "is_active", nullable = false)
    private boolean active;
}
