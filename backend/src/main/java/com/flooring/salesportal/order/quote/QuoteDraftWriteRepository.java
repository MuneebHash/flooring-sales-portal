package com.flooring.salesportal.order.quote;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Native-SQL write path for the quote draft (Phase 16C PR1). The read-side {@link QuoteDraft} /
 * {@link QuoteDraftLine} entities are read-only, so the save uses a focused native upsert + a
 * full-replace of the child lines — the same deliberate pattern as {@link com.flooring.salesportal.order.OrderEnquiryWriteRepository}.
 *
 * <p>{@link #upsertDraft} is a single {@code INSERT ... ON CONFLICT (order_id) DO UPDATE} that
 * respects {@code uq_quote_draft_order} (one draft per order can never be duplicated), preserves
 * {@code created_at} on replace, refreshes {@code updated_at}, and {@code RETURNING}s the draft id.
 * Lines are wholesale-replaced ({@link #deleteLines} then {@link #insertLines}) so the persisted set
 * always equals the server-computed set. Must run inside the caller's {@code @Transactional} method.
 */
@Repository
public class QuoteDraftWriteRepository {

    // ON CONFLICT targets uq_quote_draft_order UNIQUE (order_id). created_at is deliberately NOT in
    // the SET list so it is preserved on update; updated_at is refreshed to :ts. RETURNING yields the
    // draft id for both the insert and the update branch (DO UPDATE always returns the row).
    private static final String UPSERT_DRAFT_SQL = """
            INSERT INTO quote_draft
                (order_id, itemised, quote_total_ex_gst, quote_total_inc_gst, created_at, updated_at)
            VALUES
                (:orderId, :itemised, :quoteTotalExGst, :quoteTotalIncGst, :ts, :ts)
            ON CONFLICT (order_id) DO UPDATE SET
                itemised            = EXCLUDED.itemised,
                quote_total_ex_gst  = EXCLUDED.quote_total_ex_gst,
                quote_total_inc_gst = EXCLUDED.quote_total_inc_gst,
                updated_at          = EXCLUDED.updated_at
            RETURNING quote_draft_id
            """;

    private static final String DELETE_LINES_SQL =
            "DELETE FROM quote_draft_line WHERE quote_draft_id = :quoteDraftId";

    private static final String INSERT_LINE_SQL = """
            INSERT INTO quote_draft_line
                (quote_draft_id, line_type, description, quantity, unit_price_ex_gst,
                 line_total_ex_gst, sort_order)
            VALUES
                (:quoteDraftId, :lineType, :description, :quantity, :unitPriceExGst,
                 :lineTotalExGst, :sortOrder)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public QuoteDraftWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert or full-update the single draft row for {@code orderId}; returns its {@code quote_draft_id}. */
    public long upsertDraft(long orderId,
                            boolean itemised,
                            java.math.BigDecimal quoteTotalExGst,
                            java.math.BigDecimal quoteTotalIncGst,
                            LocalDateTime timestamp) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("itemised", itemised)
                .addValue("quoteTotalExGst", quoteTotalExGst)
                .addValue("quoteTotalIncGst", quoteTotalIncGst)
                .addValue("ts", Timestamp.valueOf(timestamp));
        Long id = jdbc.queryForObject(UPSERT_DRAFT_SQL, params, Long.class);
        if (id == null) {
            // Defensive: an upsert with DO UPDATE always RETURNINGs the row.
            throw new IllegalStateException("quote_draft upsert returned no id for order " + orderId);
        }
        return id;
    }

    public void deleteLines(long quoteDraftId) {
        jdbc.update(DELETE_LINES_SQL, new MapSqlParameterSource("quoteDraftId", quoteDraftId));
    }

    /** Insert the full server-computed line set for the draft (after {@link #deleteLines}). */
    public void insertLines(long quoteDraftId, List<QuoteComputedLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = lines.stream()
                .map(line -> new MapSqlParameterSource()
                        .addValue("quoteDraftId", quoteDraftId)
                        .addValue("lineType", line.lineType())
                        .addValue("description", line.description())
                        .addValue("quantity", line.quantity())
                        .addValue("unitPriceExGst", line.unitPriceExGst())
                        .addValue("lineTotalExGst", line.lineTotalExGst())
                        .addValue("sortOrder", line.sortOrder()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT_LINE_SQL, batch);
    }
}
