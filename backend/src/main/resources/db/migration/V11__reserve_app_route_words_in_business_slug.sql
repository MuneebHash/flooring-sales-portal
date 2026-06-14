-- V11__reserve_app_route_words_in_business_slug.sql
-- Reserve frontend app/system route words so they cannot be used as business slugs.
--
-- Background: issue #27 put the business slug in the URL (/{slug}/login,
-- /{slug}/dashboard, ...). The frontend treats no-slug app paths such as
-- /dashboard, /orders and /select-store as MISSING-slug paths and redirects them
-- to the marketing site. A business whose slug equalled one of those words would
-- shadow a real app route, so the reserved-slug CHECK is widened to cover every
-- reserved app/system word.
--
-- This REPLACES (does not duplicate) the V5 constraint chk_business_slug_reserved.
-- Every word from the original V5 list is kept; the following words are added:
--   dashboard, orders, select-store, assets, new, logout, account, settings, public
-- No original reserved word is removed.
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
        'logout', 'account', 'settings', 'public'
    ));
