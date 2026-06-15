-- ============================================================================
-- DEV-ONLY SEED — multi-store demo user (MS1) + all-users-all-stores access (Phase 14D)
-- ============================================================================
-- This is NOT a Flyway migration. It is onboarding/demo data, run MANUALLY against
-- a local database AFTER Flyway has applied V1–V12.
--
-- Safe to keep here: Flyway scans ONLY `classpath:db/migration`
-- (spring.flyway.locations in application.properties), and `db/dev-seed` is a SIBLING
-- of `db/migration`, never a descendant — so Flyway will never auto-apply this file.
-- `spring.sql.init.mode=never` also disables Spring's schema.sql/data.sql mechanism.
--   * Do NOT move this file under db/migration.
--   * Do NOT rename it to V13__... (it is not a versioned migration).
--
-- Run it locally with, e.g.:
--   psql "postgresql://flooring_user:flooring_pass@localhost:5432/flooring_sales_portal" \
--        -f backend/src/main/resources/db/dev-seed/multi_store_user_demo.sql
--
-- Idempotent: wrapped in a transaction. Part A is an UPSERT — it updates MS1 in place if
-- it already exists, otherwise inserts it; MS1 is never deleted, so its user_id and any
-- FK-linked records (orders, etc.) are preserved across re-runs. The 9 legacy V4 users
-- are never touched. Part B grants every user every store in that user's OWN business and
-- skips rows that already exist, so re-running is repeatable and additive-only.
--
-- All ids are resolved via subqueries / joins — no hardcoded business_id / store_id /
-- user_id. The Aussie business is resolved by business.slug = 'aussie-floors-group';
-- stores are resolved by the user↔store business relationship.
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- PART A — upsert the MS1 multi-store demo user (Aussie Floors Group)
-- ----------------------------------------------------------------------------
-- MS1 may already exist (a prior run) or be absent (fresh DB / it has never been a
-- backend seed user — only a frontend mock/doc). Either way, end with exactly one MS1 in
-- Aussie Floors Group with the intended values. password = "password123"; password_hash
-- below is the V7 working bcrypt hash (the V4 placeholder hash does NOT verify
-- password123 — do not use it).
--
-- This is an update-or-insert, NOT a delete/recreate: MS1's user_id stays stable and any
-- rows that reference it (e.g. orders) are preserved.

-- A.1 — if MS1 already exists in Aussie Floors Group, restore it to the intended state.
UPDATE app_user u
SET first_name    = 'Morgan',
    last_name     = 'Shaw',
    email         = 'morgan.shaw@aussiefloors.com.au',
    password_hash = '$2a$10$0gPygrnySenLQRxPuc6yFuEMKMCZRDigDt4Kn0T1KpNYqJzbBVi0a',
    is_active     = TRUE,
    updated_at    = now()
FROM business b
WHERE u.business_id = b.business_id
  AND b.slug = 'aussie-floors-group'
  AND u.salesperson_code = 'MS1';

-- A.2 — if MS1 does not exist yet, insert it (id-free: IDENTITY assigns user_id;
-- is_active / timestamps default). NOT EXISTS makes this a no-op when MS1 is already present.
INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash)
SELECT b.business_id,
       'Morgan',
       'Shaw',
       'MS1',
       'morgan.shaw@aussiefloors.com.au',
       '$2a$10$0gPygrnySenLQRxPuc6yFuEMKMCZRDigDt4Kn0T1KpNYqJzbBVi0a'
FROM business b
WHERE b.slug = 'aussie-floors-group'
  AND NOT EXISTS (
      SELECT 1
      FROM app_user u
      WHERE u.business_id = b.business_id
        AND u.salesperson_code = 'MS1'
  );

-- ----------------------------------------------------------------------------
-- PART B — grant every user access to every store in their OWN business
-- ----------------------------------------------------------------------------
-- Demo rule: each app_user can access every store whose business matches the user's
-- business (app_user.business_id = store.business_id). The denormalized business_id on
-- user_store_access is populated from that same shared business_id.
--
-- Runs AFTER Part A so MS1 is included. Additive-only: ON CONFLICT on the
-- uq_user_store_access_user_store (user_id, store_id) unique constraint skips any pair
-- that already has an access row (so legacy single-store grants are preserved and no
-- duplicates are created).
--
-- Practical effect: Aussie users (Liam/Sophie/Jack/Emma + MS1) each gain BOTH
-- SYD-CBD and SYD-PARR; Premier users are unchanged (Premier has a single store).
INSERT INTO user_store_access (business_id, user_id, store_id)
SELECT u.business_id, u.user_id, s.store_id
FROM app_user u
JOIN store s ON s.business_id = u.business_id
ON CONFLICT (user_id, store_id) DO NOTHING;

COMMIT;

-- ----------------------------------------------------------------------------
-- Optional post-run sanity check (read-only; run manually if you want to confirm):
--   SELECT u.salesperson_code, COUNT(*) AS store_count
--   FROM user_store_access usa
--   JOIN app_user u ON u.user_id = usa.user_id
--   JOIN business b ON b.business_id = u.business_id
--   WHERE b.slug = 'aussie-floors-group'
--   GROUP BY u.salesperson_code
--   ORDER BY u.salesperson_code;
-- Expect every Aussie user (incl. MS1) to report store_count = 2.
-- ----------------------------------------------------------------------------
