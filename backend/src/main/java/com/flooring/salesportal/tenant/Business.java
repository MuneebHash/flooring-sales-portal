package com.flooring.salesportal.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "business")
@Getter
@Setter
@NoArgsConstructor
public class Business {

    @Id
    @Column(name = "business_id")
    private Long businessId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "is_active", nullable = false)
    private boolean active;
}
