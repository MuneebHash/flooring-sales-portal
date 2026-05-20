# Flooring Sales Portal — Project Memory

## What this is
SaaS sales portal for flooring stores (franchise-style). Replaces paper-based quoting with a single in-home sales workflow: capture customer, search products, apply pricing and costings, record notes/photos, take payment, create invoice. Not a generic CRM. Specialised, vertical product. Multi-tenant: each store's data is isolated.

## Critical workflow rules — read every session

- Never commit unless the user explicitly says "commit". Show diffs and build output; stop short of git commit.
- `main` is protected. All work happens on feature branches. Confirm the current branch with `git branch --show-current` before editing.
- Always run npm run build from /frontend before claiming done. Report errors verbatim. Do not say "done" if the build is broken.
- Do not modify backend/, docs/, *.sql migrations, or openapi.yaml unless the user explicitly asks.
- Frontend prototype is merged on `main`. It uses static mock data and React-memory auth only. No backend/API calls yet. No localStorage or sessionStorage. This stays true until Phase 9 wires real APIs.
- Existing committed mock screens should not be redesigned unless the user explicitly asks for visual refinement.
- Match locked terminology exactly. Never invent statuses, enums, terms, or order number formats. If unsure, ask.
- Minimal scope. Do what is asked. Do not freelance scope from later sprints into the current one.

## Tech stack
- Frontend: Vite + React 19 + TypeScript + Tailwind CSS v4 + React Router
- Node 22, npm 10
- Backend: Spring Boot + PostgreSQL + Flyway. Migrations V1-V6 are locked.

## Build / dev commands
All run from /frontend:
- npm run dev — local dev server, usually http://localhost:5173 or 5174
- npm run build — production build using tsc -b && vite build. Run before saying done.
- npm run preview — preview production build

## Locked domain values — never invent variants

### Order statuses
- LEAD → Lead
- NEW_ACHIEVED_SALE → New Sale
- FOLLOW_UP → Follow Up
- ACCEPTED → Accepted
- LAID → Laid
- CANCELLED → Cancelled

Forbidden invented statuses: NEW, IN_PROGRESS, INVOICED, COMPLETED, WON, LOST, DRAFT, PAID, READY.
Keep the enum value as the state-of-truth in code. Render the human label in the UI.

### Flooring types
- SOFT → Soft flooring
- HARD → Hard flooring
- Conversion used for flooring quantity entry where applicable: 1 LM = 3.66 SQM.

### Order number format
{store_code}.{salesperson_code}.{order_sequence_number_padded_5}
Example: SYD-CBD.LC1.00001. Backend-generated in the real system; frontend prototype may display locked mock order numbers only.

### LAID rule
When order_status = LAID, the order is locked: no edits to lines, addresses, customer, or details of sale. Status may still be changed via the dashboard dropdown. Backend returns 422 ORDER_LOCKED for blocked edits.

## Mock auth scenarios
- LC1 / password123 → single-store user Liam Carter → auto-routes to Dashboard
- MS1 / password123 → multi-store user Morgan Shaw → routes to Store Selection
- Any other combination → inline error: "Invalid salesperson code or password."

## Default mock session identity
- Business: Aussie Floors Group, slug aussie-floors-group
- Active store: Aussie Floors Sydney CBD, SYD-CBD
- Salesperson: Liam Carter, LC1

## Frontend file structure
- frontend/src/App.tsx — router + auth guard
- frontend/src/components/ui/ — shared primitives: Button, Panel, Badge, Modal, Input, Field, Tabs
- frontend/src/components/workspace/ — Order Workspace tab components
- frontend/src/components/*.tsx — screen-level components
- frontend/src/data/ — mock data: mockOrders, mockAuth, mockOrderDetails
- frontend/src/lib/ — auth context, flooring helpers, status helpers

When adding new shared primitives, place them in src/components/ui/ and match the existing Tailwind style. Do not invent a parallel design system per screen.

## Design system rules
- Corporate, compact, clean, floating-panel layout.
- Clean app-screen background. No sidebar. No bulky app shell. No marketing imagery. No login photo backgrounds.
- Must fit iPad 1024x768 with no horizontal scrolling. Desktop 1440 should look polished.
- Order numbers use mono styling. Status badges are compact and professional.
- Reuse existing primitives. If a needed primitive does not exist, create it under src/components/ui/ first, then use it.

## Order Workspace tab order
1. Customer — contains two sub-tabs:
   - Details: customer details fields
   - Addresses: installation address + billing address
2. Products & Charges
3. Details of Sale
4. Notes & Photos
5. Payments
6. Invoice

## MVP scope — out of scope
- Operations Portal UI
- Installer / laybook workflows
- Advanced quote comparison / multi-quote
- Room-level complexity
- AI features
- Refunds, surcharges, finance products
- localStorage / sessionStorage persistence
- Mid-session store switching. User must log out to change store.

## Where the real rules live
For backend, API, or business-rule questions, read these files directly. Do not guess:
- docs/Phases.md  ← current phase, time budget, build order
- docs/Phase-6-Frontend-Backend-Handoff.md  ← mock limitations, field map, hardcoded values to replace
- docs/API-Conventions.md
- docs/API-Contracts-Chunk-1.md
- docs/API-Contracts-Chunk-2.md
- docs/API-Contracts-Chunk-3.md
- docs/API-Contracts-Chunk-4.md
- docs/openapi.yaml
- backend/src/main/resources/db/migration/V*.sql

If a rule in this file conflicts with docs/API-Conventions.md, the conventions doc wins.

## Reporting back when a task is done
Output in this order every time:
1. Changed files, modified + new
2. git diff --stat
3. npm run build result, success line or full error output
4. Brief tree of frontend/src/ if structure changed
5. Do not commit. Wait for the user to say commit.