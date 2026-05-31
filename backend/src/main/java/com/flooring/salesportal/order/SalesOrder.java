package com.flooring.salesportal.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * First JPA entity for the {@code sales_order} header (Phase 10A).
 *
 * <p>Read model for {@code GET /orders/{orderId}}. The {@code POST /orders} create path is
 * handled by {@link OrderCreateRepository} with native SQL so the advisory lock, sequence
 * allocation, and enum INSERT all run predictably inside one transaction; this entity is not
 * persisted via JPA. The PostgreSQL enum columns ({@code flooring_type}, {@code order_status},
 * {@code lay_date_status}) are mapped as {@code String} — the PostgreSQL JDBC driver returns the
 * enum label through {@code getString()} on read, which avoids Hibernate enum cast-name handling.
 */
@Entity
@Table(name = "sales_order")
@Getter
@NoArgsConstructor
public class SalesOrder {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "business_id")
    private Long businessId;

    @Column(name = "store_id")
    private Integer storeId;

    @Column(name = "order_sequence_number")
    private Integer orderSequenceNumber;

    @Column(name = "order_number")
    private String orderNumber;

    @Column(name = "flooring_type")
    private String flooringType;

    @Column(name = "order_status")
    private String orderStatus;

    @Column(name = "supply_only")
    private boolean supplyOnly;

    @Column(name = "plan_numbers")
    private String planNumbers;

    @Column(name = "proposed_lay_date")
    private LocalDate proposedLayDate;

    @Column(name = "lay_date_status")
    private String layDateStatus;

    @Column(name = "sale_price_ex_gst")
    private BigDecimal salePriceExGst;

    @Column(name = "total_cost")
    private BigDecimal totalCost;

    @Column(name = "gp")
    private BigDecimal gp;

    @Column(name = "gp_percent")
    private BigDecimal gpPercent;

    @Column(name = "week_number")
    private Integer weekNumber;

    @Column(name = "week_year")
    private Integer weekYear;

    @Column(name = "details_of_sale")
    private String detailsOfSale;

    @Column(name = "last_emailed_at")
    private LocalDateTime lastEmailedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
