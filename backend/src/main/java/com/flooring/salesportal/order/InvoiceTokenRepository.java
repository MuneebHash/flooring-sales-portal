package com.flooring.salesportal.order;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Phase 16G PR 4 - native-SQL access for the order-bound public invoice link ({@code invoice_token},
 * {@code V21}; 16G contract §7), in the {@link InvoiceRepository} / {@code QuoteVersionRepository}
 * style: {@link NamedParameterJdbcTemplate} with {@code RETURNING} and a plain row record.
 *
 * <p>The link belongs to the ORDER, not to one invoice version: every public request resolves the
 * order's current invoice. Only the token's SHA-256 hash is stored, and {@code token_hash} is selected
 * only onto the SERVER-INTERNAL {@link InvoiceTokenRow} for the public path's constant-time
 * re-verification; the row record is never serialized into any response.
 *
 * <p><b>Transitions.</b> Every status change is a single status-guarded statement
 * ({@code ... AND status = 'ACTIVE'}): a dead row ({@code REPLACED} / {@code REWRITTEN} /
 * {@code SIGNED} / {@code EXPIRED}) never changes status again, and its status stays the reason the
 * public page shows. Rows are never deleted. The callers run every transition under the order row
 * lock (the protected writers' scoped {@code FOR UPDATE}, the public surface's unscoped one), which,
 * with the {@code V21} partial unique index {@code uq_invoice_token_one_active_per_order}, keeps at
 * most one {@code ACTIVE} link per order.
 */
@Repository
public class InvoiceTokenRepository {

    private static final String TOKEN_COLUMNS = """
                invoice_token_id,
                order_id,
                token_hash,
                status,
                sent_by_user_id,
                sent_at,
                expires_at,
                emailed_at,
                dead_at
            """;

    // A send mints the order's new ACTIVE link (after killing any previous one as REPLACED in the same
    // transaction). The plaintext token is never persisted: only its hash.
    private static final String INSERT_ACTIVE_SQL = """
            INSERT INTO invoice_token (order_id, token_hash, status, sent_by_user_id, sent_at, expires_at)
            VALUES (:orderId, :tokenHash, 'ACTIVE', :sentByUserId, :sentAt, :expiresAt)
            RETURNING invoice_token_id
            """;

    // Hash-keyed lookup (uq_invoice_token_hash) for the public token gate. Reads ONLY invoice_token: a
    // quote token is never consulted here (16G contract §7.8).
    private static final String FIND_BY_HASH_SQL = "SELECT\n" + TOKEN_COLUMNS
            + "FROM invoice_token\n"
            + "WHERE token_hash = :tokenHash";

    // One token by id: the locked re-read of the public accept and lazy expiry, and the snapshot
    // re-read of the public GET / pdf.
    private static final String FIND_BY_ID_SQL = "SELECT\n" + TOKEN_COLUMNS
            + "FROM invoice_token\n"
            + "WHERE invoice_token_id = :invoiceTokenId";

    // The order's single ACTIVE link (at most one by uq_invoice_token_one_active_per_order).
    private static final String FIND_ACTIVE_BY_ORDER_SQL = "SELECT\n" + TOKEN_COLUMNS
            + "FROM invoice_token\n"
            + "WHERE order_id = :orderId AND status = 'ACTIVE'";

    // Order-bound kill with a REASON (REPLACED by a new send, REWRITTEN by D.2, SIGNED by D.8 / Path A).
    // 0 rows (no live link) is a no-op; under the order lock at most one row moves.
    private static final String KILL_ACTIVE_BY_ORDER_SQL = """
            UPDATE invoice_token SET status = :status, dead_at = :deadAt
            WHERE order_id = :orderId AND status = 'ACTIVE'
            """;

    // One presented token ACTIVE -> SIGNED (the public accept) or ACTIVE -> EXPIRED (lazy expiry), by id.
    private static final String KILL_ACTIVE_BY_ID_SQL = """
            UPDATE invoice_token SET status = :status, dead_at = :deadAt
            WHERE invoice_token_id = :invoiceTokenId AND status = 'ACTIVE'
            """;

    // The success-only link-email marker of the ONE token a send delivered (never "whichever token is
    // ACTIVE now"). It records a fact about that token's email, so it is not status-guarded.
    private static final String STAMP_EMAILED_AT_SQL = """
            UPDATE invoice_token SET emailed_at = :emailedAt
            WHERE invoice_token_id = :invoiceTokenId
            """;

    private static final String STATUS_EXPIRED = "EXPIRED";

    private final NamedParameterJdbcTemplate jdbc;

    public InvoiceTokenRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert the order's new {@code ACTIVE} link (hash only) with the sender, the send time and the
     * expiry captured by the send transaction, and return its id. The caller has already killed any
     * previous {@code ACTIVE} link of the order in the same order-locked transaction.
     */
    public long insertActive(long orderId, String tokenHash, long sentByUserId,
                             LocalDateTime sentAt, LocalDateTime expiresAt) {
        Long id = jdbc.queryForObject(INSERT_ACTIVE_SQL, new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("tokenHash", tokenHash)
                .addValue("sentByUserId", sentByUserId)
                .addValue("sentAt", Timestamp.valueOf(sentAt))
                .addValue("expiresAt", Timestamp.valueOf(expiresAt)), Long.class);
        if (id == null) {
            throw new IllegalStateException("invoice_token insert did not return an id");
        }
        return id;
    }

    /** The token row for a presented token's hash, or empty (unknown token → 404, no leak). */
    public Optional<InvoiceTokenRow> findByTokenHash(String tokenHash) {
        return jdbc.query(FIND_BY_HASH_SQL, new MapSqlParameterSource("tokenHash", tokenHash), ROW_MAPPER)
                .stream().findFirst();
    }

    /** One token row by id (a fresh SELECT: the locked and snapshot re-reads), or empty. */
    public Optional<InvoiceTokenRow> findById(long invoiceTokenId) {
        return jdbc.query(FIND_BY_ID_SQL, new MapSqlParameterSource("invoiceTokenId", invoiceTokenId), ROW_MAPPER)
                .stream().findFirst();
    }

    /** The order's {@code ACTIVE} link, or empty (whatever its expiry; callers compare it themselves). */
    public Optional<InvoiceTokenRow> findActiveByOrderId(long orderId) {
        return jdbc.query(FIND_ACTIVE_BY_ORDER_SQL, new MapSqlParameterSource("orderId", orderId), ROW_MAPPER)
                .stream().findFirst();
    }

    /**
     * Kill the order's {@code ACTIVE} link with {@code status} as its reason and {@code deadAt} as the
     * time it left {@code ACTIVE}. Status-guarded, so a dead row keeps its true reason; returns the number
     * of rows moved (0 when the order has no live link - a no-op - and at most 1 under the order lock).
     * An over-age link that no public request has flipped yet takes THIS reason, not {@code EXPIRED}.
     */
    public int killActiveByOrderId(long orderId, String status, LocalDateTime deadAt) {
        return jdbc.update(KILL_ACTIVE_BY_ORDER_SQL, new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("status", status)
                .addValue("deadAt", Timestamp.valueOf(deadAt)));
    }

    /**
     * Kill ONE token, still {@code ACTIVE}, with {@code status} as its reason (the public accept's
     * {@code SIGNED}). Status-guarded; returns the number of rows moved (1 when the caller holds the order
     * lock and has just re-read the token {@code ACTIVE}).
     */
    public int killActiveById(long invoiceTokenId, String status, LocalDateTime deadAt) {
        return jdbc.update(KILL_ACTIVE_BY_ID_SQL, new MapSqlParameterSource()
                .addValue("invoiceTokenId", invoiceTokenId)
                .addValue("status", status)
                .addValue("deadAt", Timestamp.valueOf(deadAt)));
    }

    /**
     * Lazy expiry of one over-age token: {@code ACTIVE -> EXPIRED} with {@code dead_at}. Only an
     * {@code ACTIVE} token can expire - a dead token is structurally untouchable by the status guard.
     * Returns true when THIS call performed the flip. Never touches an invoice row.
     */
    public boolean expireActive(long invoiceTokenId, LocalDateTime deadAt) {
        return killActiveById(invoiceTokenId, STATUS_EXPIRED, deadAt) == 1;
    }

    /**
     * Stamp the success-only {@code emailed_at} of the ONE token whose link email was just delivered.
     * Returns the number of rows updated. Link sends never touch {@code invoice.last_emailed_at} or the
     * {@code sales_order.last_emailed_at} mirror.
     */
    public int stampEmailedAt(long invoiceTokenId, LocalDateTime emailedAt) {
        return jdbc.update(STAMP_EMAILED_AT_SQL, new MapSqlParameterSource()
                .addValue("invoiceTokenId", invoiceTokenId)
                .addValue("emailedAt", Timestamp.valueOf(emailedAt)));
    }

    private static final RowMapper<InvoiceTokenRow> ROW_MAPPER = (rs, n) -> new InvoiceTokenRow(
            rs.getLong("invoice_token_id"),
            rs.getLong("order_id"),
            rs.getString("token_hash"),
            rs.getString("status"),
            rs.getLong("sent_by_user_id"),
            toLocalDateTime(rs.getTimestamp("sent_at")),
            toLocalDateTime(rs.getTimestamp("expires_at")),
            toLocalDateTime(rs.getTimestamp("emailed_at")),
            toLocalDateTime(rs.getTimestamp("dead_at")));

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /**
     * One {@code invoice_token} row. SERVER-INTERNAL: it carries {@code tokenHash} solely for the public
     * gate's constant-time re-verification and {@code sentByUserId} for the creator of a remotely signed
     * version - this record must never be serialized into any response (the public view exposes only the
     * derived state and the presented link's {@code expires_at}; InvoiceDetail only the derived
     * {@code invoice_link_expires_at}).
     */
    public record InvoiceTokenRow(
            long invoiceTokenId,
            long orderId,
            String tokenHash,
            String status,
            long sentByUserId,
            LocalDateTime sentAt,
            LocalDateTime expiresAt,
            LocalDateTime emailedAt,
            LocalDateTime deadAt) {
    }
}
