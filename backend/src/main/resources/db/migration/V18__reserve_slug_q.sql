-- V18__reserve_slug_q.sql
-- Reserve 'q' as a business slug (closes #105).
--
-- Background: Phase 16E-C added the top-level, SLUGLESS public quote page route /q/{token}
-- (locked decision — the customer link in quote emails). 'q' is therefore a frontend app route
-- word: a business whose slug equalled 'q' would have every app URL (/q/login, /q/dashboard, ...)
-- shadowed by the public quote page, exactly the failure class V11 exists to prevent — and the
-- V5 slug-format CHECK legally permits a single-character slug, so 'q' was registrable.
--
-- This REPLACES (does not duplicate) the V11 constraint chk_business_slug_reserved.
-- Every word from the V11 list is kept; the following word is added:
--   q
-- No original reserved word is removed. The frontend mirror list (RESERVED_BUSINESS_SLUGS in
-- frontend/src/lib/tenant.ts) is updated in the same change so the two lists stay in lockstep.
--
-- Seeded slugs (aussie-floors-group, premier-flooring-co) are not in this list,
-- so the recreated constraint is satisfied on a fresh database.

ALTER TABLE business
    DROP CONSTRAINT chk_business_slug_reserved;

ALTER TABLE business
    ADD CONSTRAINT chk_business_slug_reserved
    CHECK (slug NOT IN (
        'admin', 'api', 'login', 'static', 'auth', 'health',
        'dashboard', 'orders', 'select-store', 'assets', 'new',
        'logout', 'account', 'settings', 'public', 'q'
    ));
