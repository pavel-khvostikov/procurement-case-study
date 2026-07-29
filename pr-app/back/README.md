# pr-app/back — Purchase Request backend

FastAPI + SQLAlchemy on Postgres. Managed with [uv](https://docs.astral.sh/uv/).

## Run with docker compose (recommended)

From the repo root:

```bash
docker compose up --build
```

The stack brings up Postgres, runs `init-db` then `seed-users`, then starts the API on port `8001`. OpenAPI docs: http://localhost:8001/docs.

The Invoice backend uses `PR_INTEGRATION_TOKEN` to call the service-facing
purchase request API. Docker Compose supplies the same development value to
both backends.

## Run locally without Docker

You'll need a Postgres reachable on `localhost:5432` (or override via env).

```bash
cd pr-app/back

# Resolve and install deps into .venv (uses uv.lock)
uv sync

# One-shot containers, run as local commands
POSTGRES_HOST=localhost uv run pr-init-db
POSTGRES_HOST=localhost uv run pr-seed-users

# Long-running API (use the same token in the Invoice backend)
PR_INTEGRATION_TOKEN=local-dev-pr-integration-token \
  POSTGRES_HOST=localhost \
  uv run uvicorn app.main:app --reload --port 8001
```

Connection settings come from environment variables. Either set `DATABASE_URL` directly, or use the `POSTGRES_*` variables (host/port/user/password/db). See `app/db.py`.

For host-based Invoice development, configure its PR base URL as
`http://localhost:8001`. The integration token is a backend-only credential:
do not expose it to frontend environment variables or browser code.

## Project layout

```
pr-app/back/
├── pyproject.toml       # uv project
├── uv.lock              # locked deps (commit this)
├── Dockerfile           # uv-based image, used by all three services
└── app/
    ├── main.py          # FastAPI app
    ├── db.py            # SQLAlchemy engine + session
    ├── models.py        # User, PurchaseRequest, Session
    ├── schemas.py       # Pydantic DTOs
    ├── security.py      # Browser sessions + integration-token auth
    ├── pdf.py           # PR export
    ├── routers/         # auth, users, purchase_requests, integration
    └── scripts/
        ├── init_db.py   # `pr-init-db` console script
        └── seed_users.py # `pr-seed-users` console script
```

## Endpoints

| Method | Path | Auth | What it does |
| --- | --- | --- | --- |
| `POST` | `/auth/login` | — | Body `{username, password}` → sets `pr_token` cookie |
| `POST` | `/auth/logout` | cookie | Clears cookie |
| `GET`  | `/auth/me` | cookie | Returns the current user |
| `POST` | `/create-user` | cookie (finance) | Create a user |
| `PUT`  | `/edit-user/{user_id}` | cookie (finance) | Edit a user |
| `GET`  | `/purchase-request` | cookie | List PRs |
| `POST` | `/purchase-request-new` | cookie | Create a PR |
| `PUT`  | `/purchase-request/{id}` | cookie | Update a PR (fields + role-gated status transitions) |
| `GET`  | `/purchase-request/{id}/pdf` | cookie | Export the PR as a PDF |
| `GET` | `/integration/purchase-requests?status=approved` | `X-Integration-Token` | List approved PR references for Invoice |
| `GET` | `/integration/purchase-requests/{request_code}` | `X-Integration-Token` | Look up one PR reference in any status |

Integration responses contain only `request_code`, `request_name`,
`request_author`, `supplier_name`, and `request_approval_status`. The candidate
endpoint is deterministically ordered and returns approved requests only. The
exact endpoint returns non-approved requests as well, allowing the Invoice
backend to distinguish an ineligible PR from a missing one.

## Status machine

```
initiated  --(author)-->  sent for approval  --(finance)-->  approved
                                              \--(finance)-->  rejected
```

Only the request author can move `initiated → sent for approval`. Only `finance` users can move `sent for approval → approved | rejected`. Approved/rejected requests are terminal.
