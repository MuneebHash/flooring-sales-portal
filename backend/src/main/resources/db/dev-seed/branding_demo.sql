-- Dev-only seed: demo invoice logo for the Aussie Floors Group demo business (Phase 16A PR2).
-- Safe to rerun (idempotent — sets a fixed value). Targets ONLY business.slug = 'aussie-floors-group'.
--
-- DEV/DEMO logo path — read this before changing it:
--   * This sets business.logo_path = '/uploads/1/branding/logo.png' (business_id 1 =
--     'aussie-floors-group'). The SAME value is used by BOTH the screen and the backend PDF.
--   * FRONTEND SCREEN: the Invoice tab renders business.logo_path as a browser <img>. The PNG is
--     committed at frontend/public/uploads/1/branding/logo.png, so Vite serves it from the path
--     above. Fail-soft: a blank/missing/unloadable path falls back to the business-name text.
--   * BACKEND PDF: the invoice PDF treats logo_path as a SERVER FILE PATH — it reads the file from
--     local storage via FileStorageService and base64-embeds it (PNG/JPEG only). The physical file
--     must be copied into backend storage; the SQL seed alone is NOT enough. Run, from the repo root:
--         bash backend/src/main/resources/db/dev-seed/copy_demo_logo.sh
--     which copies the PNG to $HOME/flooring-sales-portal-data/uploads/uploads/1/branding/logo.png
--     (the doubled 'uploads/uploads' is intentional — app.storage.base-dir already ends in /uploads
--     and stored virtual paths start with /uploads/...). If that backend copy is missing/invalid the
--     PDF fails soft to the business-name text (no error).
--   * This is dev/demo wiring only. Real per-tenant logo upload/storage/serving is deferred to Phase 17.

BEGIN;

UPDATE business
SET logo_path = '/uploads/1/branding/logo.png'
WHERE slug = 'aussie-floors-group';

COMMIT;

-- Verify
SELECT business_id, slug, name, logo_path
FROM business
WHERE slug = 'aussie-floors-group';
