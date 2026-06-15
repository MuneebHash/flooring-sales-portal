package com.flooring.salesportal.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A per-business reusable "details of sale" quick-add line (V12 table
 * {@code business_quick_description}).
 *
 * <p>The text column is named {@code description} (NOT {@code text} — {@code text} is a Postgres
 * type-name keyword). A {@code description} may contain the literal token {@code [BUSINESS_NAME]};
 * the token is stored verbatim and substituted with the business name at read time by
 * {@link QuickDescriptionService} (never expanded in the DB, never substituted client-side).
 *
 * <p>Rows are read-only through JPA — they are seeded by standalone dev SQL (not a Flyway
 * migration), so {@code created_at}/{@code updated_at} are DB-managed and mapped read-only.
 */
@Entity
@Table(name = "business_quick_description")
@Getter
@Setter
@NoArgsConstructor
public class BusinessQuickDescription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "business_quick_description_id")
    private Long businessQuickDescriptionId;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    // DB-managed (DEFAULT now()); read-only here — never written via JPA.
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
