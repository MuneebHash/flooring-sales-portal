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
versioned `V<n>__…` names — they are not versioned migrations.

## Go-forward rule (locked)

```
schema       = Flyway migration (db/migration, versioned, locked once committed)
demo/dev data = db/dev-seed (manual, idempotent, run by hand)
new tests    = self-seed the data they need; do NOT depend on the V4 legacy seed
```

New backend tests must create/seed the rows they assert on (state-derived), rather than
relying on the legacy V4 demo data remaining in the migration path.

## Manual run order

Run every command **from the repo root**. Host `psql` is not installed on the dev Mac, so
each SQL seed runs `psql` **inside the Postgres container** (`flooring-sales-portal-postgres`
from `infra/docker-compose.yml`) with the script file redirected into it. `ON_ERROR_STOP=1`
makes a failing statement stop the run instead of carrying on.

1. Start local Postgres:
   ```
   docker compose -f infra/docker-compose.yml up -d
   ```
2. Start the backend so Flyway applies all migrations (V1–V18), then leave it running:
   ```
   cd backend && ./mvnw spring-boot:run
   ```
   Run the remaining steps from the repo root in a second terminal, in this order.
3. Quick-add descriptions seed:
   ```
   docker exec -i flooring-sales-portal-postgres psql -U flooring_user -d flooring_sales_portal -v ON_ERROR_STOP=1 < backend/src/main/resources/db/dev-seed/quick_descriptions_demo.sql
   ```
4. Multi-store user + all-stores-access seed:
   ```
   docker exec -i flooring-sales-portal-postgres psql -U flooring_user -d flooring_sales_portal -v ON_ERROR_STOP=1 < backend/src/main/resources/db/dev-seed/multi_store_user_demo.sql
   ```
5. Payment helpers seed (demo bank-transfer details + Stripe payment link for the Payments tab):
   ```
   docker exec -i flooring-sales-portal-postgres psql -U flooring_user -d flooring_sales_portal -v ON_ERROR_STOP=1 < backend/src/main/resources/db/dev-seed/payment_helpers_demo.sql
   ```
6. Per-flooring-type invoice terms seed (HTML numbered terms for the Invoice tab):
   ```
   docker exec -i flooring-sales-portal-postgres psql -U flooring_user -d flooring_sales_portal -v ON_ERROR_STOP=1 < backend/src/main/resources/db/dev-seed/terms_demo.sql
   ```
7. Demo invoice logo branding seed:
   ```
   docker exec -i flooring-sales-portal-postgres psql -U flooring_user -d flooring_sales_portal -v ON_ERROR_STOP=1 < backend/src/main/resources/db/dev-seed/branding_demo.sql
   ```
8. Copy the demo logo PNG into backend file storage so the invoice **PDF** can render it
   (the SQL seed alone is not enough — the PDF reads the file from disk):
   ```
   bash backend/src/main/resources/db/dev-seed/copy_demo_logo.sh
   ```
9. Verify in the app: login, store selection, quick-adds, products, charges, payment helpers,
   terms, and the invoice PDF logo.
   - Login at `/aussie-floors-group/login` as **`MS1` / `password123`**.
   - Store selection shows **SYD-CBD** and **SYD-PARR** (two stores).
   - Details of Sale tab shows the **7** quick-add descriptions.
   - Product search and charge lines still work.
   - Payments tab shows the demo bank-transfer details and the Stripe payment-link button.
   - Invoice tab shows the demo hard/soft terms; the invoice PDF shows the demo logo
     (old stored PDFs do not update — rewrite/regenerate the invoice to see it).
   - (Optional) `LC1` / `SN1` / `JW1` / `EP1` now also have both Aussie stores.

## Files

- `quick_descriptions_demo.sql` — seeds the 7 locked quick-add description templates for
  the demo businesses (Phase 14C). Targets businesses by slug.
- `multi_store_user_demo.sql` — recreates the **MS1** (Morgan Shaw) multi-store demo user
  in Aussie Floors Group, then grants every user access to every store in their own
  business (Phase 14D).
- `payment_helpers_demo.sql` — sets demo bank-transfer details (`bank_name`, `bsb`,
  `account_number`, `account_name`) and a demo `stripe_payment_link_url` on the
  **`aussie-floors-group`** business only, for the Payments tab helpers (Phase 15E), then
  prints the values back. Fixed values, so re-running is harmless.
- `terms_demo.sql` — seeds the demo per-flooring-type invoice terms (`terms_soft` /
  `terms_hard`) for every business as **safe HTML ordered lists**, so the Invoice tab renders
  proper numbered, two-column terms. Updates every business; business name is substituted
  from `business.name`.
- `branding_demo.sql` — sets `business.logo_path = '/uploads/1/branding/logo.png'` for the
  **`aussie-floors-group`** demo business (business_id 1). One value drives **both** surfaces:
  - **Screen:** the Invoice tab renders it as a browser `<img>`; the PNG is committed at
    `frontend/public/uploads/1/branding/logo.png`, so Vite serves it directly (no copy needed).
  - **PDF:** the backend reads `logo_path` as a server file path (PNG/JPEG only) from local
    storage and base64-embeds it. The physical file must be copied into backend storage with
    `copy_demo_logo.sh` (below) — the SQL seed alone does not place the file. If the backend
    copy is missing/invalid the PDF fails soft to the business-name text (no error).
- `copy_demo_logo.sh` — copies `frontend/public/uploads/1/branding/logo.png` into local backend
  storage at `$HOME/flooring-sales-portal-data/uploads/uploads/1/branding/logo.png`. The doubled
  `uploads/uploads` is **intentional**: `app.storage.base-dir` already ends in `/uploads` and
  stored virtual paths start with `/uploads/...`, so `FileStorageService` resolves them under the
  base dir (same as attachments at `…/uploads/uploads/{businessId}/orders/…`). Dev/demo only —
  real per-tenant logo upload/storage/serving is deferred to Phase 17.

## Idempotency convention

- Every script is safe to re-run. `quick_descriptions_demo.sql`,
  `multi_store_user_demo.sql` and `branding_demo.sql` are wrapped in a single
  `BEGIN; … COMMIT;`; `payment_helpers_demo.sql` and `terms_demo.sql` are a single
  deterministic `UPDATE` followed by a read-back `SELECT`.
- Cleanup deletes **child rows before parent rows**.
- `business_quick_description` has a foreign key to `business` with **no `ON DELETE`**
  clause, so any teardown/seed cleanup must delete `business_quick_description` rows
  before the `business` row — ordering matters.

## Phase 17 deferral

Current migrations are V1–V18. The legacy V4–V7 demo data (Aussie Floors Group / Premier
Flooring, users LC1…EL1, the seeded orders/invoice, and the `password123` hashes) **stays in
the migration path for now** — committed migrations are never edited, the CI "Locked migration
protection" guard covers V1–V13, and the backend test suite depends on the legacy data. The
production-safe schema-only baseline / migration squash is **deferred to Phase 17**, before the
first real store's data exists, done on a controlled branch with a verified
fresh-DB-from-zero test, not by deleting old migrations.
