package com.flooring.salesportal.order;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Native-SQL write path for {@code PUT /orders/{orderId}/enquiry} (Phase 15F lead enquiry form).
 *
 * <p>The read-side {@link OrderEnquiry} entity is intentionally read-only (no generated id, no
 * setters, timestamps unmapped), so enquiry save uses a focused native upsert rather than JPA —
 * the same deliberate pattern as {@link OrderCustomerWriteRepository}. A single
 * {@code INSERT ... ON CONFLICT (order_id) DO UPDATE} expresses the full-replace upsert atomically
 * and respects {@code uq_order_enquiry_order}, so a second row for the same {@code order_id} can
 * never be created.
 *
 * <p>Binds all 19 enquiry data columns. The four tri-state columns ({@code fully_installed},
 * {@code uplift}, {@code furniture}, {@code stairs}) are bound as nullable {@link Boolean}; a null
 * persists as SQL NULL (unanswered) and is never turned into {@code false}. Must be invoked from a
 * {@code @Transactional} service method ({@link OrderService#saveEnquiry}).
 */
@Repository
public class OrderEnquiryWriteRepository {

    // ON CONFLICT targets uq_order_enquiry_order UNIQUE (order_id):
    //   - no row        -> INSERT (created_at + updated_at both set from :ts)
    //   - existing row  -> UPDATE every enquiry column from EXCLUDED (full replace, omitted
    //                      optionals already bound as NULL / coerced false), updated_at refreshed
    //                      to :ts, created_at deliberately NOT in the SET list so it is preserved.
    private static final String UPSERT_SQL = """
            INSERT INTO order_enquiry
                (order_id, lead_type, carpet, hard_floor,
                 product_fit_timing, product_usage_context, rooms_to_cover, textured_or_plain,
                 light_or_dark_tones, colours_interested, current_flooring_and_reason,
                 products_discussed,
                 fully_installed, uplift, furniture, stairs,
                 subfloor_concrete, subfloor_timber, subfloor_tile,
                 brief_summary, created_at, updated_at)
            VALUES
                (:orderId, :leadType, :carpet, :hardFloor,
                 :productFitTiming, :productUsageContext, :roomsToCover, :texturedOrPlain,
                 :lightOrDarkTones, :coloursInterested, :currentFlooringAndReason,
                 :productsDiscussed,
                 :fullyInstalled, :uplift, :furniture, :stairs,
                 :subfloorConcrete, :subfloorTimber, :subfloorTile,
                 :briefSummary, :ts, :ts)
            ON CONFLICT (order_id) DO UPDATE SET
                lead_type                   = EXCLUDED.lead_type,
                carpet                      = EXCLUDED.carpet,
                hard_floor                  = EXCLUDED.hard_floor,
                product_fit_timing          = EXCLUDED.product_fit_timing,
                product_usage_context       = EXCLUDED.product_usage_context,
                rooms_to_cover              = EXCLUDED.rooms_to_cover,
                textured_or_plain           = EXCLUDED.textured_or_plain,
                light_or_dark_tones         = EXCLUDED.light_or_dark_tones,
                colours_interested          = EXCLUDED.colours_interested,
                current_flooring_and_reason = EXCLUDED.current_flooring_and_reason,
                products_discussed          = EXCLUDED.products_discussed,
                fully_installed             = EXCLUDED.fully_installed,
                uplift                      = EXCLUDED.uplift,
                furniture                   = EXCLUDED.furniture,
                stairs                      = EXCLUDED.stairs,
                subfloor_concrete           = EXCLUDED.subfloor_concrete,
                subfloor_timber             = EXCLUDED.subfloor_timber,
                subfloor_tile               = EXCLUDED.subfloor_tile,
                brief_summary               = EXCLUDED.brief_summary,
                updated_at                  = EXCLUDED.updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OrderEnquiryWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert or full-replace the single enquiry row for {@code orderId}. Text values are passed
     * already-trimmed or {@code null}; the three NOT NULL product/subfloor flags are concrete
     * booleans; the four tri-state flags are nullable {@link Boolean}. Must run inside the caller's
     * transaction.
     */
    public void upsert(long orderId,
                       String leadType,
                       boolean carpet,
                       boolean hardFloor,
                       String productFitTiming,
                       String productUsageContext,
                       String roomsToCover,
                       String texturedOrPlain,
                       String lightOrDarkTones,
                       String coloursInterested,
                       String currentFlooringAndReason,
                       String productsDiscussed,
                       Boolean fullyInstalled,
                       Boolean uplift,
                       Boolean furniture,
                       Boolean stairs,
                       boolean subfloorConcrete,
                       boolean subfloorTimber,
                       boolean subfloorTile,
                       String briefSummary,
                       LocalDateTime timestamp) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("leadType", leadType)
                .addValue("carpet", carpet)
                .addValue("hardFloor", hardFloor)
                .addValue("productFitTiming", productFitTiming)
                .addValue("productUsageContext", productUsageContext)
                .addValue("roomsToCover", roomsToCover)
                .addValue("texturedOrPlain", texturedOrPlain)
                .addValue("lightOrDarkTones", lightOrDarkTones)
                .addValue("coloursInterested", coloursInterested)
                .addValue("currentFlooringAndReason", currentFlooringAndReason)
                .addValue("productsDiscussed", productsDiscussed)
                .addValue("fullyInstalled", fullyInstalled)
                .addValue("uplift", uplift)
                .addValue("furniture", furniture)
                .addValue("stairs", stairs)
                .addValue("subfloorConcrete", subfloorConcrete)
                .addValue("subfloorTimber", subfloorTimber)
                .addValue("subfloorTile", subfloorTile)
                .addValue("briefSummary", briefSummary)
                .addValue("ts", Timestamp.valueOf(timestamp));
        jdbc.update(UPSERT_SQL, params);
    }
}
