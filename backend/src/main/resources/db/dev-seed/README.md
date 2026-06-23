# `db/dev-seed` — manual dev/demo seed scripts

This directory holds **manual dev/demo seed data only**. It is **not** part of the
schema and is **never auto-applied**.

- Flyway scans **only** `classpath:db/migration` (`spring.flyway.locations` in
  `application.properties`). `db/dev-seed` is a **sibling** of `db/migration`, never a
  descendant, so Flyway will never discover or run these files.
- `spring.sql.init.mode=never` also disables Spring's `schema.sql` / `data.sql`
  mechanism.
- These scripts must **never run in production**. They seed demo tenants/users/data for
  local development and demos.

Do **not** move these files under `db/migration`, and do **not** rename them to
`V13__…` — they are not versioned migrations.

## Go-forward rule (locked)

```
schema       = Flyway migration (db/migration, versioned, locked once committed)
demo/dev data = db/dev-seed (manual, idempotent, run by hand)
new tests    = self-seed the data they need; do NOT depend on the V4 legacy seed
```

New backend tests must create/seed the rows they assert on (state-derived), rather than
relying on the legacy V4 demo data remaining in the migration path.

## Manual run order

1. Start local Postgres:
   `docker compose -f infra/docker-compose.yml up -d`
2. Start the backend so Flyway applies all migrations (V1–V12):
   `cd backend && ./mvnw spring-boot:run`
3. Run the quick-add descriptions seed:
   ```
   psql "postgresql://flooring_user:flooring_pass@localhost:5432/flooring_sales_portal" \
     -f backend/src/main/resources/db/dev-seed/quick_descriptions_demo.sql
   ```
4. Run the multi-store user + all-stores-access seed:
   ```
   psql "postgresql://flooring_user:flooring_pass@localhost:5432/flooring_sales_portal" \
     -f backend/src/main/resources/db/dev-seed/multi_store_user_demo.sql
   ```
5. Run the per-flooring-type invoice terms seed (HTML numbered terms for the Invoice tab):
   ```
   psql "postgresql://flooring_user:flooring_pass@localhost:5432/flooring_sales_portal" \
     -f backend/src/main/resources/db/dev-seed/terms_demo.sql
   ```
6. Run the demo invoice logo (screen-only branding) seed:
   ```
   psql "postgresql://flooring_user:flooring_pass@localhost:5432/flooring_sales_portal" \
     -f backend/src/main/resources/db/dev-seed/branding_demo.sql
   ```
7. Verify in the app: login, store selection, quick-adds, products, and charges.
   - Login at `/aussie-floors-group/login` as **`MS1` / `password123`**.
   - Store selection shows **SYD-CBD** and **SYD-PARR** (two stores).
   - Details of Sale tab shows the **7** quick-add descriptions.
   - Product search and charge lines still work.
   - (Optional) `LC1` / `SN1` / `JW1` / `EP1` now also have both Aussie stores.

## Files

- `quick_descriptions_demo.sql` — seeds the 7 locked quick-add description templates for
  the demo businesses (Phase 14C). Targets businesses by slug.
- `multi_store_user_demo.sql` — recreates the **MS1** (Morgan Shaw) multi-store demo user
  in Aussie Floors Group, then grants every user access to every store in their own
  business (Phase 14D).
- `terms_demo.sql` — seeds the demo per-flooring-type invoice terms (`terms_soft` /
  `terms_hard`) for every business as **safe HTML ordered lists**, so the Invoice tab renders
  proper numbered, two-column terms. Updates every business; business name is substituted
  from `business.name`.
- `branding_demo.sql` — sets `business.logo_path` for the **`aussie-floors-group`** demo
  business to a frontend public asset (`/demo-logos/aussie-floors-logo.svg`) so the Invoice
  tab shows a demo logo. **Screen-only** for now — the backend PDF treats `logo_path` as a
  server file path (PNG/JPEG only), so the PDF logo is out of scope and falls back to the
  business-name text (Phase 16A PR1).

## Idempotency convention

- Every script is wrapped in a single `BEGIN; … COMMIT;` and is safe to re-run.
- Cleanup deletes **child rows before parent rows**.
- `business_quick_description` has a foreign key to `business` with **no `ON DELETE`**
  clause, so any teardown/seed cleanup must delete `business_quick_description` rows
  before the `business` row — ordering matters.

## Phase 16 deferral

The legacy V4–V7 demo data (Aussie Floors Group / Premier Flooring, users LC1…EL1, the
seeded orders/invoice, and the `password123` hashes) **stays in the migration path for
now** — it is locked (CI guards V1–V12) and the backend test suite depends on it. The
production-safe schema-only baseline / migration squash is **deferred to Phase 16 /
pre-deploy**, done on a controlled branch with a verified fresh-DB-from-zero test, not by
deleting old migrations.
