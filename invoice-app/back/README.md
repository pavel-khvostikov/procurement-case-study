# invoice-app/back — Invoice payment backend

Java 17 + Spring Boot 3.3 + Spring Data JPA on Postgres. Build with Maven.

## Run with docker compose (recommended)

From the repo root:

```bash
docker compose up --build invoice-app-back
```

The Spring Boot app listens on `http://localhost:8002`. Once it's up:

- **Swagger UI** → http://localhost:8002/swagger-ui.html
- **OpenAPI JSON** → http://localhost:8002/v3/api-docs
- **OpenAPI YAML** → http://localhost:8002/v3/api-docs.yaml

In Swagger UI, run `POST /auth/login` with `{ "username": "finadmin", "password": "password123" }` first — the response sets the `invoice_token` cookie, after which every other endpoint becomes callable directly from the "Try it out" buttons.

## Run locally

```bash
cd invoice-app/back
# requires JDK 17 and Maven 3.9+
export PR_API_BASE_URL=http://localhost:8001
export PR_INTEGRATION_TOKEN=<same-token-configured-on-pr-backend>
mvn spring-boot:run
```

Set `POSTGRES_HOST` etc. via environment vars to point at a non-default Postgres. The `users` table is owned by the PR backend (`pr-init-db`), so make sure that container has run first — otherwise login fails before this app's tables even matter.

`PR_API_BASE_URL` defaults to `http://localhost:8001`. The integration token
defaults to blank and must be configured before loading candidates or creating
or changing a PR relationship. It is a backend credential and must never be
exposed through a `VITE_*` variable. Calls use finite connection and response
timeouts and have no retries or cache.

## Endpoints

All routes except `/auth/login` and `/auth/logout` require an `invoice_token` cookie issued by `/auth/login`. The cookie's user must additionally have `role = finance` for any `/invoice` route.

| Method | Path | Body | Notes |
| --- | --- | --- | --- |
| `POST` | `/auth/login` | `{username, password}` | Sets `invoice_token` cookie. 401 if creds bad. 403 if role ≠ finance. |
| `POST` | `/auth/logout` | — | Clears the cookie and the server-side session row. |
| `GET`  | `/auth/me` | — | Echoes the current user. 401 if not signed in. |
| `GET`  | `/invoice` | — | List all invoices. Does not include attachment bytes. |
| `GET`  | `/invoice?purchase_request_number=PR-2` | — | Exact local filter returning only validated links. |
| `GET`  | `/invoice/purchase-requests` | — | Proxy approved PR candidates from the PR backend. |
| `POST` | `/invoice` | `multipart/form-data` | Create. Fields: `invoice_number`, `supplier`, required `purchase_request_number`, `invoice_sum`, `invoice_sum_paid`, `invoice_status`, `attachment` (file, optional). Paid amount may be omitted only for `created`, where it defaults to zero. |
| `PUT`  | `/invoice/{id}` | JSON | Partial update. An omitted PR preserves it; a changed code or explicit legacy reconciliation validates it. Null/blank cannot clear it. |
| `GET`  | `/invoice/{id}/attachment` | — | Streams the PDF. 404 if no file attached. |

Invoice responses include `purchase_request_validated_at`. A PR code with a
timestamp is a trusted relationship; a code without one is legacy/unverified;
neither value means a legacy unlinked invoice. Legacy data remains readable,
but only validated rows appear in relationship filtering.

Create, relinking, and legacy reconciliation perform an authoritative PR lookup
and require current approval. Resubmitting an unchanged validated code makes no
remote call. Controlled errors use `{ "code": "...", "message": "..." }`:
invalid relationships return `422`, dependency outages return `503`, and
malformed or unauthorized upstream responses return `502`. Reads and unrelated
edits do not call the PR backend.

Invoice-local payment validation also enforces the starter's zero/partial/full
semantics: amounts must be cent-exact, totals must be positive, `created`
requires zero paid, `prepaid` requires a partial amount, and `paid` requires the
full amount. Contradictory creates or updates that submit amount/status fields
return Spring's standard `400` before PR lookup or entity mutation, outside the
`{ "code", "message" }` integration-error contract. Existing contradictory
rows are not backfilled, and unrelated updates that omit payment fields do not
revalidate them.

## Project layout

```
src/main/java/com/casestudy/invoiceapp/
├── InvoiceApplication.java   # Spring Boot entry point
├── config/
│   └── WebConfig.java        # CORS + BCryptPasswordEncoder bean
├── user/
│   ├── User.java             # Read-only JPA view of shared `users` table
│   └── UserRepository.java
├── auth/
│   ├── InvoiceSession.java   # JPA entity for `invoice_sessions`
│   ├── SessionRepository.java
│   ├── AuthController.java   # /auth/login, /auth/logout, /auth/me
│   ├── CurrentUserFilter.java # OncePerRequestFilter — reads cookie, stuffs user into request
│   └── AuthDtos.java         # LoginRequest, UserResponse
├── invoice/
│   ├── Invoice.java          # JPA entity, includes attachment_bytes
│   ├── InvoiceRepository.java # findAllSummaries() projects past the byte[]
│   ├── InvoiceService.java   # relationship and payment invariants
│   ├── InvoiceController.java # user API + download endpoint
│   └── dto/
│       ├── InvoiceSummaryDto.java
│       └── InvoiceUpdateDto.java
└── purchaserequest/
    ├── PurchaseRequestClient.java
    ├── PurchaseRequestReference.java
    ├── PurchaseRequestException.java
    ├── PurchaseRequestExceptionHandler.java
    └── PurchaseRequestRelationshipService.java
```

## Integration boundary

This app and the PR backend still own separate domain data:

1. Invoice stores only the PR's canonical `request_code` and validation provenance; it does not read PR tables or copy PR details.
2. The browser calls this backend, which sends `X-Integration-Token` to the PR backend.
3. Both applications still connect to the same Postgres instance and read the shared `users` table as a starter-code constraint.

Sessions remain intentionally separate; signing in to one application does not sign in to the other.
