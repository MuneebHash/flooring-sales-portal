package com.flooring.salesportal.order.quote;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 16C PR1 — V16 DB backstop tests (review-fix pass). Exercises the migration constraints
 * directly with JdbcTemplate (these tables are schema-only in PR1; only the DB enforces them):
 * the partial unique indexes (one ISSUED quote_version / one ACTIVE quote_token), the
 * ITEM/ADJUSTMENT shape CHECK on quote_version_line, and the non-negative sort_order CHECKs on both
 * line tables. Self-seeds its own order; runs in the test transaction and rolls back.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class QuoteMigrationConstraintsTest {

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int seq = 60_000;

    private long insertOrder() {
        int s = ++seq;
        String orderNumber = "QMIGT.ZZ9." + String.format("%05d", s % 100_000);
        return jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 1, 2026) RETURNING order_id",
                Long.class, BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, s, orderNumber);
    }

    private long insertQuoteVersion(long orderId, int versionNumber, String status) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO quote_version (order_id, version_number, status, itemised, quote_total_ex_gst, "
                        + " quote_total_inc_gst, flooring_type_snapshot, created_by_user_id) "
                        + "VALUES (?, ?, ?, TRUE, 100.00, 110.00, 'SOFT', ?) RETURNING quote_version_id",
                Long.class, orderId, versionNumber, status, USER_LIAM);
    }

    private void insertQuoteToken(long versionId, String status, String hash) {
        jdbcTemplate.update(
                "INSERT INTO quote_token (quote_version_id, token_hash, status, expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '7 days')",
                versionId, hash, status);
    }

    private long insertQuoteDraft(long orderId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO quote_draft (order_id, itemised, quote_total_ex_gst, quote_total_inc_gst) "
                        + "VALUES (?, TRUE, 0.00, 0.00) RETURNING quote_draft_id",
                Long.class, orderId);
    }

    // ---- FIX 1: one ISSUED quote_version per order ----

    @Test
    void duplicateIssuedQuoteVersion_sameOrder_rejected() {
        long orderId = insertOrder();
        insertQuoteVersion(orderId, 1, "ISSUED");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertQuoteVersion(orderId, 2, "ISSUED"));
    }

    @Test
    void oneIssuedPlusMultipleNonIssued_sameOrder_allowed() {
        long orderId = insertOrder();
        insertQuoteVersion(orderId, 1, "ISSUED");      // the single live issued version
        insertQuoteVersion(orderId, 2, "SUPERSEDED");
        insertQuoteVersion(orderId, 3, "SUPERSEDED");
        insertQuoteVersion(orderId, 4, "ACCEPTED");
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertEquals(4, count, "one ISSUED alongside several non-ISSUED versions is allowed");
    }

    // ---- FIX 2: one ACTIVE quote_token per version ----

    @Test
    void duplicateActiveToken_sameVersion_rejected() {
        long orderId = insertOrder();
        long versionId = insertQuoteVersion(orderId, 1, "ISSUED");
        insertQuoteToken(versionId, "ACTIVE", "hash-active-1");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertQuoteToken(versionId, "ACTIVE", "hash-active-2"));
    }

    @Test
    void oneActivePlusDeadTokens_sameVersion_allowed() {
        long orderId = insertOrder();
        long versionId = insertQuoteVersion(orderId, 1, "ISSUED");
        insertQuoteToken(versionId, "ACTIVE", "hash-a");
        insertQuoteToken(versionId, "REPLACED", "hash-b");
        insertQuoteToken(versionId, "CONSUMED", "hash-c");
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_token WHERE quote_version_id = ?", Integer.class, versionId);
        Assertions.assertEquals(3, count, "dead tokens coexist with one ACTIVE token (kept for messaging)");
    }

    // ---- FIX 3: line-table shape + non-negative sort_order backstops ----

    @Test
    void invalidQuoteVersionLineShape_itemWithNullQuantity_rejected() {
        long orderId = insertOrder();
        long versionId = insertQuoteVersion(orderId, 1, "ISSUED");
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO quote_version_line (quote_version_id, line_type, description, quantity, "
                        + " unit_price_ex_gst, line_total_ex_gst, sort_order) "
                        + "VALUES (?, 'ITEM', 'bad item', NULL, NULL, 100.00, 0)", versionId));
    }

    @Test
    void negativeSortOrder_quoteDraftLine_rejected() {
        long orderId = insertOrder();
        long draftId = insertQuoteDraft(orderId);
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO quote_draft_line (quote_draft_id, line_type, description, quantity, "
                        + " unit_price_ex_gst, line_total_ex_gst, sort_order) "
                        + "VALUES (?, 'ITEM', 'x', 1.00, 100.00, 100.00, -1)", draftId));
    }

    @Test
    void negativeSortOrder_quoteVersionLine_rejected() {
        long orderId = insertOrder();
        long versionId = insertQuoteVersion(orderId, 1, "ISSUED");
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO quote_version_line (quote_version_id, line_type, description, quantity, "
                        + " unit_price_ex_gst, line_total_ex_gst, sort_order) "
                        + "VALUES (?, 'ITEM', 'x', 1.00, 100.00, 100.00, -1)", versionId));
    }
}
