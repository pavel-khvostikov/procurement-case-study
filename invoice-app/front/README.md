# invoice-app/front — Invoice Payment UI

Vite + React 19 + Mantine 8 + Axios. Same stack as `pr-app/front`. No router, no form library, no state manager.

## Run with docker compose

From the repo root:

```bash
docker compose up --build invoice-app-front
```

Then open http://localhost:5174. Sign in as `finadmin / password123`. The other seed users (`alice`, `bob`) will hit a 403 — this app is finance-only.

## Run locally

```bash
cd invoice-app/front
npm install
npm run dev
```

The frontend expects the Spring backend at `http://localhost:8002`. Override with `VITE_API_URL=http://...` if needed.

## Project layout

```
src/
├── main.jsx                # MantineProvider + AuthProvider + App
├── App.jsx                 # auth gate: <Loader> | <LoginPage> | <InvoiceList>
├── api.js                  # axios client, baseURL 8002, withCredentials: true
├── auth.jsx                # AuthContext + useAuth() hook
├── hooks/
│   └── usePurchaseRequests.js  # approved-PR loading and retry state
└── components/
    ├── LoginPage.jsx       # finance-only login card with restricted-access note
    ├── InvoiceList.jsx     # table, exact validated-PR filter, and summary cards
    ├── SummaryCards.jsx    # 4 stat cards computed client-side
    ├── PurchaseRequestSelect.jsx # shared searchable approved-PR selector
    ├── UploadInvoiceModal.jsx
    ├── EditInvoiceModal.jsx
    └── StatusBadge.jsx     # status → Mantine Badge (created / prepaid / paid)
```

## Auth behaviour

`POST /auth/login` on the Spring backend returns 403 if the user's role is anything other than `finance`. The login form catches that and shows "This app is finance-only. Sign in with a finance account."

## Things worth noting

- **Approved PR selection.** Create and edit use `GET /invoice/purchase-requests` on the Invoice backend. The selector searches by PR code, request name, supplier, and requester; the PR integration token remains backend-only.
- **Save-time validation.** Selector results improve the finance workflow, but the Invoice backend validates the selected PR again before saving. Selecting a PR prefills supplier while keeping it editable and showing a nonblocking mismatch warning.
- **Legacy-safe editing.** Invoice responses use `purchase_request_validated_at` to distinguish validated links, unverified legacy text, and unlinked invoices. If candidates are unavailable, creation is disabled, while existing relationship details and unrelated invoice edits remain available.
- **Exact relationship filtering.** Validated PR codes in the table are clickable and reload `GET /invoice?purchase_request_number=...`. Legacy text is labelled unverified and never participates in that relationship view.
- **Summary cards are client-side.** They're computed from the loaded list in `SummaryCards.jsx`. A future iteration should add a backend `/invoice/summary` endpoint so the numbers stay correct under pagination.
- **PDF attachments via cookie-protected URLs.** Download links point at `GET /invoice/{id}/attachment`, which only succeeds with a valid `invoice_token` cookie. That's why we open them with `target="_blank"` rather than fetching the bytes through axios.
