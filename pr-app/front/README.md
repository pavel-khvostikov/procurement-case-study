# pr-app/front — Purchase Request UI

Vite + React 19 + Mantine 8 + Axios. Everything else is on purpose absent — no router, no state manager, no UI utility library, no form library.

## Run with docker compose

From the repo root:

```bash
docker compose up --build pr-app-front
```

Then open http://localhost:5173.

## Run locally

```bash
cd pr-app/front
npm install
npm run dev
```

The frontend expects the API at `http://localhost:8001`. Override with `VITE_API_URL=http://...` if needed.

## Project layout

```
src/
├── main.jsx              # MantineProvider + AuthProvider + App
├── App.jsx               # auth gate: <Loader> | <LoginPage> | <PRList>
├── api.js                # axios client (withCredentials: true)
├── auth.jsx              # AuthContext + useAuth() hook
└── components/
    ├── LoginPage.jsx     # username / password card
    ├── PRList.jsx        # main screen: header + table + search
    ├── CreatePRModal.jsx # "Create New Purchase Request" form
    ├── ViewPRModal.jsx   # role-aware view modal
    ├── InvoiceStatusPanel.jsx # degradable requester/finance status view
    └── StatusBadge.jsx   # status → Mantine Badge color
```

## Role-aware actions in the View modal

The view modal renders different action buttons based on the current user and the request's status:

| User vs PR | Status `initiated` | Status `sent for approval` | `approved` / `rejected` |
| --- | --- | --- | --- |
| Author (any role) | **Send for approval** | — | — |
| Finance (not author) | — | **Reject**, **Approve** | — |
| Finance + author | **Send for approval** | (you can't approve your own) | — |
| Other employees | — | — | — |

`PDF` is always available — it just hits `/purchase-request/{id}/pdf`.

The backend re-checks every transition (`_can_transition` in `routers/purchase_requests.py`); the frontend gating is just to avoid showing buttons that would fail.

## Invoice-status panel

The PR detail modal shows recorded invoice statuses to finance and to the
request owner identified by stable `author_id`. It loads through the PR backend,
never contacts Invoice directly, and handles loading, no linked invoices, and a
retryable dependency failure independently from the rest of the modal.

The panel deliberately shows only invoice number and status. It does not expose
amounts or claim that the purchase request as a whole is fully paid.
