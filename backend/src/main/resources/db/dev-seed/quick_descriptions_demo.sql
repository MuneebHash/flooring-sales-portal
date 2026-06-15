-- ============================================================================
-- DEV-ONLY SEED — quick-add descriptions demo data (Phase 14C)
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
--        -f backend/src/main/resources/db/dev-seed/quick_descriptions_demo.sql
--
-- Idempotent: wrapped in a transaction; deletes the target businesses' existing
-- quick-add rows, then re-inserts the 7 locked templates (sort_order 1–7) for each.
-- Targets businesses BY SLUG (not hardcoded business_id); a slug that does not exist
-- is simply skipped. The literal token [BUSINESS_NAME] is stored verbatim — the
-- backend substitutes it with business.name at read time (never expand it here).
-- ============================================================================

BEGIN;

-- Demo businesses seeded by V4/V5 (business_id 1 / 2). Add slugs here to seed more.
-- Remove only the rows for the targeted tenants so re-running is repeatable and
-- never touches other businesses.
DELETE FROM business_quick_description
WHERE business_id IN (
    SELECT business_id FROM business
    WHERE slug IN ('aussie-floors-group', 'premier-flooring-co')
);

INSERT INTO business_quick_description (business_id, description, sort_order)
SELECT b.business_id, t.description, t.sort_order
FROM business b
CROSS JOIN (VALUES
    ('[BUSINESS_NAME] to supply and install ________ colour ________ on ________ to ________.', 1),
    ('Pull up and disposal of existing ________ included by [BUSINESS_NAME].', 2),
    ('No pull up and disposal required by [BUSINESS_NAME].', 3),
    ('No furniture to be moved by [BUSINESS_NAME].', 4),
    ('Furniture to be moved by [BUSINESS_NAME]. Any disassembly or personal/delicate items must be moved before installers arrive.', 5),
    ('Based on customer measurements of ________. Site measure required.', 6),
    ('No further floor preparation has been costed or allowed for. Any additional costs are to be dealt with directly with the installer on the day.', 7)
) AS t(description, sort_order)
WHERE b.slug IN ('aussie-floors-group', 'premier-flooring-co');

COMMIT;
