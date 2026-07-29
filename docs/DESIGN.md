# Integration Design

## Problem interpretation

Before PR 2, there was no reliable, traceable relationship between an approved purchase request (PR) and its invoices.

Finance had to copy the PR code and supplier into Invoice, where the code was unvalidated text. Neither system could prove that the PR existed, was approved, or was linked to an invoice, so reconciliation happened through email, Slack, and spreadsheets.

PR 2 demonstrates that:

1. Finance selects an approved PR using business context instead of copying its code and supplier.
2. The Invoice backend validates the association before persisting it.
3. Users can see zero, one, or many invoices linked to a PR.
4. Invalid and legacy references never appear as trusted relationships.
5. Existing invoice data remains readable if the PR application is unavailable.

PR 3 adds a read-only list of linked invoice statuses for the PR author and finance. It does not infer that a PR is fully paid because PRs have no amount or currency and further invoices may arrive.

The prototype establishes the smallest reliable boundary for this handoff while leaving room for the incoming ERP and Purchase Orders.

## Current constraints

### Observed facts

- The FastAPI PR backend and Spring Boot Invoice backend have separate APIs and sessions. They share Postgres and the existing `users` table but do not read each other's domain tables.
- A PR has a unique, human-facing `request_code`. The current API does not edit or delete it.
- PR statuses are `initiated`, `sent for approval`, `approved`, and `rejected`; `approved` and `rejected` are terminal.
- Before PR 2, Invoice stored nullable `purchase_request_number` as unvalidated text; both `POST /invoice` and `PUT /invoice/{id}` accepted fabricated values.
- PRs have no requested amount, currency, Purchase Order, or durable supplier identifier. Both applications store supplier names as free text.
- Invoice owns invoice amount, paid amount, status, supplier, and attachment. Its statuses are `created`, `prepaid`, and `paid`.
- The PR backend creates shared tables through a one-shot job; Invoice relies on Hibernate `ddl-auto:update`. The starter had no CI or business-logic tests; PR 2 adds focused backend coverage but no CI.

### Assumptions adopted for the prototype

| Assumption | Consequence |
|---|---|
| One PR may have many invoices; one new invoice belongs to one PR. | Store one canonical PR code on each invoice and query by that code. |
| Only approved PRs are eligible for new links. | The selector lists approved PRs and every save validates status again. |
| New invoices in this purchase workflow require a PR. | Creation without a validated PR is rejected; existing unlinked invoices remain readable. |
| `request_code` is a stable external business identifier. | Use it instead of the database ID and make its stability part of the contract. |
| PR and invoice supplier strings may legitimately differ. | Prefill the invoice supplier and warn on mismatch, but do not block the save. |
| Existing PR strings cannot be trusted automatically. | Treat them as legacy/unverified until finance validates a selection. |
| In the starter vocabulary, `created` means unpaid, `prepaid` means partially paid, and `paid` means fully paid. | Fill the obvious status-derived amounts in the UI and reject contradictory new invoices or updates that submit amount/status fields. |

### Notes and deferred questions

These production questions do not block the prototype:

- Can one consolidated invoice cover several PRs?
- Which legitimate invoices may exist without a PR?
- What are the future PR-to-PO and PO-to-invoice cardinalities?
- Which ERP or legal-entity identifier should define supplier identity?
- What amount, currency, tax, tolerance, credit-note, and partial-payment rules define reconciliation?

Manual tests exposed a starter defect: an invoice could be saved as `paid` with zero paid, disappearing from Outstanding while contributing nothing to Paid this month. A narrow PR 2 fix validates positive, cent-exact totals and consistent zero/partial/full states on create and updates that submit amount/status fields. Selecting `paid` copies the invoice total; selecting `created` resets paid to zero; backend validation remains authoritative. Outstanding is now derived from amounts. Existing contradictory rows are not backfilled, and unrelated updates that omit payment fields remain available.

“Paid this month” still uses generic `updated_at`, so an unrelated edit can make an old payment appear current and partial payments cannot be assigned reliably to a month. Accurate payment dates, installments, reversals, and actors require a payment timestamp or ledger and remain roadmap work.

Other baseline issues outside this integration are concurrency-unsafe PR-code generation, mutable usernames beside stored author names, duplicate or blank invoice identity fields, hardcoded USD display, broad PR visibility, weak session expiry, attachment handling, and missing migrations. The PR UI also describes rejection and finance self-approval differently from the backend. These remain separate from the integration PRs.

## Proposed design

PR 2 changes the PR backend, Invoice backend, and Invoice frontend. PR 3 adds the narrow reverse contract and PR status panel. No third service or technology replacement is needed.

- The Invoice backend retrieves candidates and validates selections synchronously through a narrow PR API.
- A single Invoice service enforces the relationship invariant for create and edit; controllers cannot bypass it.
- The Invoice frontend calls only its own backend; service credentials never reach the browser.
- Invoice stores the canonical PR code and validation provenance; relationship reads use local data, not the PR API.
- The PR status panel uses a separate read-only call from PR to Invoice and degrades independently.

Neither backend reads the other's domain tables. The existing shared `users` table remains a baseline coupling and is not expanded.

The existing stack and Docker Compose topology remain. Spring uses its existing `RestClient`; the PR backend uses `httpx` for its outbound call.

Configuration is backend-only:

| Consumer | Configuration |
|---|---|
| Invoice backend calling PR | `PR_API_BASE_URL`, `PR_INTEGRATION_TOKEN` |
| PR backend calling Invoice | `INVOICE_API_BASE_URL`, `INVOICE_INTEGRATION_TOKEN` |

Docker service URLs are `http://pr-app-back:8001` and `http://invoice-app-back:8002`; host execution uses `localhost` with the same ports. Both clients have finite connection and response timeouts. Calls happen at request time, so Compose needs no circular startup dependency.

## Domain model and ownership

| Data | Owner | Prototype treatment |
|---|---|---|
| PR code, name, author, supplier, approval status | PR | Exposed through a minimal read contract. |
| Invoice number, supplier, amounts, status, attachment | Invoice | Remains Invoice-owned. |
| Invoice-to-PR association | Invoice | Stores PR's canonical `request_code`. |
| Association provenance | Invoice | Nullable `purchase_request_validated_at`; non-null means validated through the PR API. |
| Requester-visible invoice status | Invoice | Returned on demand, not copied into PR storage. |

One PR has zero or many invoices; every new invoice has one PR.

The existing column is retained with explicit semantics:

```text
purchase_request_number       = "PR-2"
purchase_request_validated_at = <validation timestamp>
```

| Stored state | Meaning |
|---|---|
| Code and validation timestamp | Trusted logical relationship. |
| Code without validation timestamp | Legacy/unverified text. |
| Neither value | Legacy/unlinked invoice. |

New invoices cannot enter the latter two states. Relationship queries and the PR status panel use only validated rows, so a matching legacy string is not silently promoted.

The relationship provides logical referential integrity without a cross-application foreign key or PR snapshot. Storing the code keeps reads available during an outage and avoids synchronizing copied fields. The invoice supplier remains independent: selection prefills it, but finance may accept a mismatch warning and retain another value.

The Invoice backend enforces these relationship invariants before persistence:

- Every new invoice has one validated, currently approved PR.
- Changing an invoice's PR validates the new code.
- An unchanged validated link does not require the PR service.
- An unchanged legacy code requires explicit validation to become trusted.
- A PR outage does not block unrelated edits to legacy or validated invoices.
- A validated link cannot be cleared in the prototype.

## Integration contracts and state flow

New payloads follow the existing snake-case JSON convention.

### PR backend integration API

Requests require `X-Integration-Token: <PR_INTEGRATION_TOKEN>`.

| Endpoint | Purpose | Response |
|---|---|---|
| `GET /integration/purchase-requests?status=approved` | Approved candidates for the Invoice selector. | Minimal PR references. |
| `GET /integration/purchase-requests/{request_code}` | Authoritative save-time lookup. | The same reference for any existing status; `404` if absent. |

A PR reference contains only:

```json
{
  "request_code": "PR-2",
  "request_name": "Annual software renewal",
  "request_author": "alice",
  "supplier_name": "Atlassian",
  "request_approval_status": "approved"
}
```

The exact lookup includes non-approved records so Invoice can distinguish “not found” from “ineligible.”

### Invoice backend user API

Existing Invoice cookie authentication and finance authorization still apply.

| Endpoint | Change |
|---|---|
| `GET /invoice/purchase-requests` | Proxy approved PR options to the UI. |
| `POST /invoice` | Require and validate `purchase_request_number`; save it with the validation timestamp. |
| `PUT /invoice/{id}` | Validate when the submitted code differs or the current link lacks provenance. |
| `GET /invoice?purchase_request_number=PR-2` | Return locally stored, validated invoices linked by exact code. |

Responses include `purchase_request_validated_at` so the UI can distinguish validated, legacy/unverified, and unlinked records.

### Invoice-status API

Requests from the PR backend require `X-Integration-Token: <INVOICE_INTEGRATION_TOKEN>`.

| Endpoint | Purpose |
|---|---|
| `GET /integration/invoices?purchase_request_number=PR-2` | Return only `id`, `invoice_number`, and `invoice_status` for validated links. |
| `GET /purchase-request/{pr_id}/invoices` | PR user-facing proxy for the stable `author_id` owner or finance. |

The PR backend resolves `pr_id` to its canonical code, so callers cannot query arbitrary codes. Only the `author_id` owner and finance can fetch the panel; a legacy PR without `author_id` is finance-only. An empty list returns `200` and is distinct from dependency failure.

### Create flow

1. The Invoice UI loads approved PRs through its backend.
2. Finance searches by code, request name, supplier, or author.
3. Selection prefills the supplier; a later mismatch produces a warning.
4. On submit, the backend looks up the PR and accepts only a current `approved` status.
5. It commits the invoice, canonical code, and validation timestamp in one local transaction.
6. Selecting a validated PR code filters the invoice list by exact code.

### Edit and legacy reconciliation

- An unchanged validated relationship allows other fields to be saved without a remote call.
- A changed code is validated before any invoice mutation.
- A legacy value appears as unverified. Selecting an approved candidate, including the same code, validates it and adds provenance.
- If candidates fail to load, the current relationship remains visible and unrelated fields remain editable, but the selector is disabled.
- Failed validation leaves the submitted invoice unchanged.

### Requester-visible status

For an authorized user, the PR backend fetches minimal invoice summaries by canonical code. The UI shows each recorded status (`created`, `prepaid`, or `paid`) without inferring balance, overall payment completion, or future invoices.

PR validation is a remote read followed by a local Invoice transaction. This does not use a distributed transaction; terminal approval and the absence of PR deletion make that acceptable. Calls are bounded, with no automatic retries, and users can retry the action.

## Failure and ambiguity handling

Purchase-request integration errors return a stable machine-readable `code` and safe `message` without exposing upstream bodies, URLs, or credentials. Failed create and relink requests persist nothing. Invoice-local payment validation uses Spring's standard `400` response and is outside the `PURCHASE_REQUEST_*` error contract.

| Case | Behaviour |
|---|---|
| New invoice omits a PR | `422 PURCHASE_REQUEST_REQUIRED`. |
| PR code does not exist | `422 PURCHASE_REQUEST_NOT_FOUND`. |
| PR exists but is not approved | `422 PURCHASE_REQUEST_NOT_APPROVED`. |
| PR timeout, connection failure, or `5xx` | `503 PURCHASE_REQUEST_SERVICE_UNAVAILABLE`. |
| PR authentication failure or malformed contract | `502 PURCHASE_REQUEST_SERVICE_ERROR`. |
| Invoice amount and payment status contradict each other | Standard `400`; persist nothing and do not call the PR service. |
| Candidate becomes ineligible after selection | Save-time lookup rejects it. |
| No approved candidates exist | Show an empty state, not an outage. |
| Candidate service is unavailable during create | Disable creation and show a retryable error; do not fall back to free text. |
| Candidate service is unavailable during edit | Keep the current relationship visible and allow unrelated edits. |
| Supplier values differ | Show both values and warn; allow the save. |
| Existing free-text reference | Mark it legacy/unverified and exclude it from relationship views until reconciled. |
| PR service unavailable during invoice reads | Serve existing invoices and validated relationships from local data. |
| Invoice timeout, connection failure, or `5xx` in the PR view | `503 INVOICE_SERVICE_UNAVAILABLE`; keep the rest of the PR usable. |
| Invoice authentication, unexpected response, or malformed contract | `502 INVOICE_SERVICE_ERROR` without upstream details. |
| No linked invoices in the PR view | Show “No linked invoices yet,” not an error or paid state. |
| Another employee requests the status panel | `403`; only finance and the stable `author_id` owner are allowed. |
| One PR has several invoices | Return each validated invoice as a separate row. |
| One invoice needs several PRs | Unsupported; never flatten multiple codes into text. |

Integration endpoints reject missing or invalid tokens. Tokens never appear in `VITE_*` variables or logs. TLS, rotation, and workload identity are production hardening.

## Testing and validation

### Automated tests

| Risk | Required coverage |
|---|---|
| Contract leaks or returns ineligible PRs | Require the token; list only approved records and documented fields; distinguish non-approved from absent PRs. |
| UI/API bypasses the invariant | Accept approved PRs; reject missing, fabricated, and non-approved PRs without persistence; prevent `PUT` bypasses. |
| Legacy text is mistaken for a relationship | Keep legacy/unlinked records readable; exclude matching unverified strings from relationship APIs; add provenance only through reconciliation. |
| Peer outage breaks unrelated work | Keep reads and unrelated invoice edits available; fail create/relink with the defined status. |
| One-to-many filtering is wrong | Return two validated invoices for one PR while excluding another PR and a matching legacy string. |
| Integration failures are ambiguous | Map timeouts, upstream `5xx`, authentication failures, and malformed payloads to controlled errors. |
| Payment state is contradictory | Accept consistent zero/partial/full states; reject invalid creates and updates that submit amount/status fields before PR lookup or mutation; keep unrelated legacy edits available. |
| PR status panel leaks invoice data | Require the token; omit amounts and attachments; permit author/finance only; distinguish empty from unavailable. |

FastAPI tests use `pytest`, `TestClient`, dependency overrides, and an isolated database. Spring tests use `MockMvc`, JPA tests, and a mocked HTTP boundary. Backend invariants are automated; the small UI paths use the demonstration below rather than a new frontend test framework.

### PR 2 verification

Verified on 2026-07-29:

| Check | Result |
|---|---|
| `git diff --check` | Passed. |
| `(cd pr-app/back && uv sync && uv run pytest)` | Host `uv` was unavailable. The equivalent official uv container run passed all 10 tests in 13.72s, with one Passlib `crypt` deprecation warning. |
| `(cd invoice-app/back && mvn test)` | Host Maven was unavailable. The equivalent Maven 3.9.9 / Java 17 container run passed all 38 tests with no failures, errors, or skips. |
| `(cd invoice-app/front && npm ci && npm run build)` | Passed after the payment fix; Vite built 812 modules. `npm ci` reported five audit findings (one low, four high) in the existing dependency set. |
| `docker compose config` | Passed. |
| `docker compose up --build -d` | Built the six application/support images and started the stack; Postgres was healthy and all four applications were reachable. The Invoice backend and frontend were rebuilt after the payment fix and remained reachable. |

### End-to-end demonstration

The running stack passed this scenario:

1. `alice` created a PR and sent it for approval; `finadmin` approved it.
2. The Invoice candidate proxy returned the approved PR, and two invoices were created with non-null provenance.
3. The exact filter returned those two invoices and excluded an invoice for another PR plus matching legacy text without provenance.
4. Fabricated and non-approved PRs returned the defined `422` errors and persisted nothing.
5. With the PR backend stopped, unfiltered reads, exact filtering, and an unrelated edit still returned `200`; candidate loading and new creation returned the defined `503`.
6. After restart, candidate loading and validated invoice creation recovered.
7. A `paid` create without a paid amount returned `400` and left the invoice count unchanged.

### PR 3 verification

Verified on 2026-07-29:

| Check | Result |
|---|---|
| `git diff --check` | Passed. |
| `(cd pr-app/back && uv sync && uv run pytest)` | Host `uv` was unavailable. From `pr-app/back`, `docker run --rm --mount type=bind,src="$PWD/tests",dst=/app/tests procurement-case-study-pr-app-back uv run pytest -q` passed 39 tests in 2.23s, with one Passlib `crypt` deprecation warning. `uv lock --check` resolved 44 packages. |
| `(cd invoice-app/back && mvn test)` | Host Maven was unavailable. From `invoice-app/back`, `docker run --rm -v "$PWD:/build" -w /build maven:3.9-eclipse-temurin-17 mvn test` passed all 43 tests with no failures, errors, or skips. |
| `(cd pr-app/front && npm ci && npm run build)` | Passed; Vite built 809 modules. |
| `(cd invoice-app/front && npm ci && npm run build)` | Passed; Vite built 812 modules. |
| `docker compose config` | Passed. |
| `docker compose up --build -d` | Built all six application/support images and started both backends and frontends. |

Both `npm ci` runs reported five audit findings (one low, four high) in the existing dependency set.

The running stack also passed the PR 3 path. The author first received an empty list, while another employee received `403`. After approval, two validated invoices appeared for both the author and finance in descending order and with exactly the three documented fields; matching legacy text remained excluded. With Invoice stopped, the PR list and PDF still returned `200` while the panel endpoint returned the controlled `503`. Restarting Invoice restored the two-row response.

## Trade-offs and alternatives

| Decision | Trade-off and rejected alternative |
|---|---|
| Change both backends rather than add an integration service | Preserves ownership through two narrow contracts. A third service would add operational cost without owning a domain. |
| Use HTTP rather than the shared database or a cross-schema foreign key | Adds a validation-time dependency but avoids schema coupling and supports future database separation. |
| Validate synchronously rather than use events | Creation needs an immediate eligibility decision. Events may support projections later but cannot replace save-time validation. |
| Use `request_code` rather than numeric PR ID | It is unique, API-immutable, human-facing, and already present in Invoice; its stability becomes part of the contract. |
| Store code plus validation timestamp rather than a snapshot | Keeps relationship reads available and records provenance without copying mutable PR data. Snapshots or contract versioning can follow if required. |
| Route browser requests through each application's own backend | Requires small proxies but keeps service tokens out of React and avoids a second user session. |
| Prefill and warn on supplier mismatch rather than enforce equality | Free-text values are not reliable identities, so equality would create false negatives. A supplier master is the longer-term solution. |
| Add a narrow reverse read for requester status | Adds a second runtime direction but directly addresses a stated pain through a minimal, read-only contract. |
| Do not model Purchase Orders yet | ERP identifiers, ownership, and cardinalities are unknown; modelling them now would encode guesses. |

## Prototype scope

The work is split into three reviewer-facing pull requests:

| Pull request | Scope |
|---|---|
| 1. Design proposal | Implemented and merged. |
| 2. Trusted PR–invoice relationship | Implemented and merged: PR read contract; Invoice client and save invariant; validation provenance; exact one-to-many query; create/edit selector and failure states; narrow Invoice payment guard found during verification; configuration; backend tests. |
| 3. Requester invoice-status visibility | Implemented in this branch: reverse read contract; stable author/finance authorization; degradable PR detail panel; configuration; tests. |

Excluded are a new service, technology migration, cross-domain table reads, PR workflow redesign, audited relinking, Purchase Orders, ERP work, automatic matching, monetary reconciliation, supplier master data, notifications, teams, budgets, and reporting. Tests, configuration, and documentation ship with each feature; there is no cleanup-only PR.

## Roadmap

### Near-term production hardening

| Priority | Step | Why this order |
|---|---|---|
| 1 | Add explicit migrations, schema ownership, an index on validated PR references, and a reviewed legacy backfill. | Safe deployment and accurate legacy treatment come before new features. |
| 2 | Replace static tokens with managed service identity and TLS; rotate secrets; version contracts; add consumer/provider contract tests. | Secure and stabilize the proven boundary. |
| 3 | Add structured logs, correlation IDs, dependency metrics, alerts, and timeout dashboards. | Distinguish business errors from peer failures before adding retries or caching. |
| 4 | Introduce a dedicated atomic relink operation recording old code, new code, actor, timestamp, and reason. | Corrections need accountability rather than generic editing. |
| 5 | Define remaining invoice integrity rules: idempotency, duplicate numbers, currency/precision, payment dates, transitions, and audit history. | Stabilize supplier and monetary semantics before automation. |
| 6 | Add server-side search and pagination; introduce caching only after measuring load and freshness requirements. | Scale from measured demand. |

### Broader product capabilities

| Priority | Capability | Dependency and rationale |
|---|---|---|
| 1 | Discover and integrate the ERP/Purchase Order model. | Confirm ownership, identifiers, lifecycle, and PR–PO–invoice cardinalities before designing storage or APIs. |
| 2 | Establish supplier identity. | Authoritative ERP or supplier-master IDs enable reliable matching and bank/tax data. |
| 3 | Model money and reconciliation. | Define requested, ordered, invoiced, and paid amounts, plus currency, tax, tolerances, credit notes, and partial-invoice rules, before calculating deltas. |
| 4 | Improve workflow visibility. | Add approval/payment notifications, teams, budgets, and requester controls once authoritative states exist. |
| 5 | Add reconciliation automation. | Build reporting, exception queues, OCR, and suggested matching on trusted identifiers and monetary rules. |

The validated PR reference is a bridge: it solves today's handoff without imposing assumptions on the future PO model.
