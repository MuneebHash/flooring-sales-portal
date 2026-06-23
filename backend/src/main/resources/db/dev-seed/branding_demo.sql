-- Dev-only seed: demo SCREEN logo for the Aussie Floors Group demo business (Phase 16A PR1).
-- Safe to rerun (idempotent — sets a fixed value). Targets ONLY business.slug = 'aussie-floors-group'.
--
-- SCREEN-ONLY demo branding — read this before changing it:
--   * The Invoice TAB renders business.logo_path as a browser <img> (fail-soft: a blank,
--     missing or unloadable path falls back to the business-name text).
--   * '/demo-logos/aussie-floors-logo.svg' is a FRONTEND PUBLIC asset, served by Vite from
--     frontend/public/. It is NOT a server-side storage key.
--   * The BACKEND invoice PDF treats logo_path as a SERVER FILE PATH (it reads the file from
--     disk via FileStorageService and base64-embeds it) and only accepts PNG/JPEG. This SVG
--     value will therefore NOT render in the PDF — the PDF fails soft to the business-name
--     text. Screen/PDF logo parity is intentionally OUT OF SCOPE for PR1.

BEGIN;

UPDATE business
SET logo_path = '/demo-logos/aussie-floors-logo.svg'
WHERE slug = 'aussie-floors-group';

COMMIT;

-- Verify
SELECT business_id, slug, name, logo_path
FROM business
WHERE slug = 'aussie-floors-group';
