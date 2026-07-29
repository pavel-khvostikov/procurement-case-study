from __future__ import annotations

from collections.abc import Callable, Iterator
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from app import invoice_client
from app.db import Base, get_db
from app.main import app
from app.models import PurchaseRequest, Session as SessionModel, User
from app.schemas import InvoiceStatusOut
from app.security import COOKIE_NAME, INTEGRATION_TOKEN_HEADER

OWNER_TOKEN = "owner-session"
OTHER_TOKEN = "other-session"
FINANCE_TOKEN = "finance-session"
INVOICE_TOKEN = "test-invoice-integration-token"


def _purchase_request(
    *,
    id: int,
    code: str,
    request_author: str,
    author_id: int | None,
    approval_status: str = "approved",
) -> PurchaseRequest:
    return PurchaseRequest(
        id=id,
        request_author=request_author,
        author_id=author_id,
        request_name=f"Request {id}",
        request_code=code,
        supplier_name=f"Supplier {id}",
        supplier_email=f"supplier-{id}@example.com",
        request_details=f"Details for request {id}",
        request_approval_status=approval_status,
    )


@pytest.fixture
def client() -> Iterator[TestClient]:
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    testing_session = sessionmaker(
        bind=engine,
        autoflush=False,
        autocommit=False,
    )
    Base.metadata.create_all(engine)

    with testing_session() as db:
        db.add_all(
            [
                User(
                    id=1,
                    username="renamed-owner",
                    password_hash="unused",
                    role="employee",
                ),
                User(
                    id=2,
                    username="stale-owner-name",
                    password_hash="unused",
                    role="employee",
                ),
                User(
                    id=3,
                    username="finance",
                    password_hash="unused",
                    role="finance",
                ),
            ]
        )
        db.flush()
        db.add_all(
            [
                _purchase_request(
                    id=10,
                    code="PR-10",
                    request_author="stale-owner-name",
                    author_id=1,
                ),
                _purchase_request(
                    id=20,
                    code="PR-20",
                    request_author="stale-owner-name",
                    author_id=None,
                ),
                _purchase_request(
                    id=30,
                    code="PR-30",
                    request_author="stale-owner-name",
                    author_id=2,
                    approval_status="initiated",
                ),
            ]
        )
        db.add_all(
            [
                SessionModel(token=OWNER_TOKEN, user_id=1),
                SessionModel(token=OTHER_TOKEN, user_id=2),
                SessionModel(token=FINANCE_TOKEN, user_id=3),
            ]
        )
        db.commit()

    def override_get_db() -> Iterator[Session]:
        with testing_session() as db:
            yield db

    app.dependency_overrides[get_db] = override_get_db
    with TestClient(app) as test_client:
        yield test_client

    app.dependency_overrides.clear()
    Base.metadata.drop_all(engine)
    engine.dispose()


def _auth_headers(token: str) -> dict[str, str]:
    return {"Cookie": f"{COOKIE_NAME}={token}"}


def _mock_httpx_client(
    monkeypatch: pytest.MonkeyPatch,
    handler: Callable[[httpx.Request], httpx.Response],
) -> dict[str, Any]:
    real_client = httpx.Client
    captured: dict[str, Any] = {}

    def client_factory(**kwargs: Any) -> httpx.Client:
        captured.update(kwargs)
        return real_client(transport=httpx.MockTransport(handler), **kwargs)

    monkeypatch.setattr(invoice_client.httpx, "Client", client_factory)
    return captured


def test_purchase_request_responses_expose_stable_nullable_author_id(
    client: TestClient,
) -> None:
    response = client.get("/purchase-request", headers=_auth_headers(OWNER_TOKEN))

    assert response.status_code == 200
    by_id = {item["id"]: item for item in response.json()}
    assert by_id[10]["author_id"] == 1
    assert by_id[20]["author_id"] is None


def test_owner_fetches_invoices_by_canonical_request_code(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []

    def fetch(request_code: str) -> list[InvoiceStatusOut]:
        calls.append(request_code)
        return [
            InvoiceStatusOut(
                id=8,
                invoice_number="INV-8",
                invoice_status="prepaid",
            ),
            InvoiceStatusOut(
                id=4,
                invoice_number="",
                invoice_status="created",
            ),
        ]

    monkeypatch.setattr(
        "app.routers.purchase_requests.fetch_invoice_statuses",
        fetch,
    )

    response = client.get(
        "/purchase-request/10/invoices",
        headers=_auth_headers(OWNER_TOKEN),
    )

    assert response.status_code == 200
    assert response.json() == [
        {
            "id": 8,
            "invoice_number": "INV-8",
            "invoice_status": "prepaid",
        },
        {"id": 4, "invoice_number": "", "invoice_status": "created"},
    ]
    assert calls == ["PR-10"]


@pytest.mark.parametrize(
    ("pr_id", "token"),
    [
        (10, FINANCE_TOKEN),
        (20, FINANCE_TOKEN),
    ],
)
def test_finance_can_fetch_current_and_legacy_purchase_request_invoices(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
    pr_id: int,
    token: str,
) -> None:
    calls: list[str] = []
    monkeypatch.setattr(
        "app.routers.purchase_requests.fetch_invoice_statuses",
        lambda code: calls.append(code) or [],
    )

    response = client.get(
        f"/purchase-request/{pr_id}/invoices",
        headers=_auth_headers(token),
    )

    assert response.status_code == 200
    assert response.json() == []
    assert calls == [f"PR-{pr_id}"]


@pytest.mark.parametrize(
    ("pr_id", "token", "expected_status"),
    [
        (10, OTHER_TOKEN, 403),
        (20, OTHER_TOKEN, 403),
        (999, OWNER_TOKEN, 404),
    ],
)
def test_unauthorized_or_missing_request_never_calls_invoice(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
    pr_id: int,
    token: str,
    expected_status: int,
) -> None:
    calls: list[str] = []
    monkeypatch.setattr(
        "app.routers.purchase_requests.fetch_invoice_statuses",
        lambda code: calls.append(code) or [],
    )

    response = client.get(
        f"/purchase-request/{pr_id}/invoices",
        headers=_auth_headers(token),
    )

    assert response.status_code == expected_status
    assert calls == []


def test_invoice_panel_requires_browser_authentication(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []
    monkeypatch.setattr(
        "app.routers.purchase_requests.fetch_invoice_statuses",
        lambda code: calls.append(code) or [],
    )

    response = client.get("/purchase-request/10/invoices")

    assert response.status_code == 401
    assert calls == []


def test_ordinary_purchase_request_operations_ignore_invoice_configuration(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("INVOICE_API_BASE_URL", raising=False)
    monkeypatch.delenv("INVOICE_INTEGRATION_TOKEN", raising=False)
    monkeypatch.setattr(
        "app.routers.purchase_requests.fetch_invoice_statuses",
        lambda _: pytest.fail("ordinary PR operations must not call Invoice"),
    )

    list_response = client.get(
        "/purchase-request",
        headers=_auth_headers(OWNER_TOKEN),
    )
    update_response = client.put(
        "/purchase-request/30",
        json={"request_name": "Updated without Invoice"},
        headers=_auth_headers(OTHER_TOKEN),
    )
    pdf_response = client.get(
        "/purchase-request/10/pdf",
        headers=_auth_headers(OWNER_TOKEN),
    )

    assert list_response.status_code == 200
    assert update_response.status_code == 200
    assert update_response.json()["request_name"] == "Updated without Invoice"
    assert pdf_response.status_code == 200
    assert pdf_response.headers["content-type"] == "application/pdf"


def test_invoice_client_sends_narrow_authenticated_request(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("INVOICE_API_BASE_URL", "http://invoice.test:8002")
    monkeypatch.setenv("INVOICE_INTEGRATION_TOKEN", INVOICE_TOKEN)

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "GET"
        assert request.url.path == "/integration/invoices"
        assert dict(request.url.params) == {
            "purchase_request_number": "PR-10",
        }
        assert request.headers[INTEGRATION_TOKEN_HEADER] == INVOICE_TOKEN
        return httpx.Response(
            200,
            json=[
                {
                    "id": 9,
                    "invoice_number": "INV-9",
                    "invoice_status": "paid",
                }
            ],
        )

    captured = _mock_httpx_client(monkeypatch, handler)

    result = invoice_client.fetch_invoice_statuses("PR-10")

    assert result == [
        InvoiceStatusOut(
            id=9,
            invoice_number="INV-9",
            invoice_status="paid",
        )
    ]
    assert captured["timeout"].connect == 2.0
    assert captured["timeout"].read == 3.0


@pytest.mark.parametrize(
    "payload",
    [
        [{"id": "9", "invoice_number": "INV-9", "invoice_status": "paid"}],
        [
            {
                "id": 9,
                "invoice_number": "INV-9",
                "invoice_status": "paid",
                "amount": 100,
            }
        ],
        [{"id": 9, "invoice_number": "INV-9", "invoice_status": "unknown"}],
    ],
)
def test_invoice_client_rejects_malformed_or_expanded_contract(
    monkeypatch: pytest.MonkeyPatch,
    payload: list[dict[str, object]],
) -> None:
    monkeypatch.setenv("INVOICE_API_BASE_URL", "http://invoice.test:8002")
    monkeypatch.setenv("INVOICE_INTEGRATION_TOKEN", INVOICE_TOKEN)
    _mock_httpx_client(
        monkeypatch,
        lambda _: httpx.Response(200, json=payload),
    )

    with pytest.raises(invoice_client.InvoiceIntegrationError) as caught:
        invoice_client.fetch_invoice_statuses("PR-10")

    assert caught.value.status_code == 502
    assert caught.value.code == invoice_client.INVOICE_SERVICE_ERROR


def test_invoice_client_rejects_malformed_json(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("INVOICE_API_BASE_URL", "http://invoice.test:8002")
    monkeypatch.setenv("INVOICE_INTEGRATION_TOKEN", INVOICE_TOKEN)
    _mock_httpx_client(
        monkeypatch,
        lambda _: httpx.Response(200, content=b"{"),
    )

    with pytest.raises(invoice_client.InvoiceIntegrationError) as caught:
        invoice_client.fetch_invoice_statuses("PR-10")

    assert caught.value.status_code == 502
    assert caught.value.code == invoice_client.INVOICE_SERVICE_ERROR


@pytest.mark.parametrize("status_code", [401, 403, 404, 409])
def test_invoice_client_maps_unexpected_upstream_status_to_502(
    monkeypatch: pytest.MonkeyPatch,
    status_code: int,
) -> None:
    monkeypatch.setenv("INVOICE_API_BASE_URL", "http://invoice.test:8002")
    monkeypatch.setenv("INVOICE_INTEGRATION_TOKEN", INVOICE_TOKEN)
    _mock_httpx_client(
        monkeypatch,
        lambda _: httpx.Response(status_code, text="sensitive upstream body"),
    )

    with pytest.raises(invoice_client.InvoiceIntegrationError) as caught:
        invoice_client.fetch_invoice_statuses("PR-10")

    assert caught.value.status_code == 502
    assert caught.value.code == invoice_client.INVOICE_SERVICE_ERROR
    assert "sensitive" not in caught.value.message


@pytest.mark.parametrize("status_code", [500, 502, 503])
def test_invoice_client_maps_upstream_5xx_to_503(
    monkeypatch: pytest.MonkeyPatch,
    status_code: int,
) -> None:
    monkeypatch.setenv("INVOICE_API_BASE_URL", "http://invoice.test:8002")
    monkeypatch.setenv("INVOICE_INTEGRATION_TOKEN", INVOICE_TOKEN)
    _mock_httpx_client(
        monkeypatch,
        lambda _: httpx.Response(status_code, text="sensitive upstream body"),
    )

    with pytest.raises(invoice_client.InvoiceIntegrationError) as caught:
        invoice_client.fetch_invoice_statuses("PR-10")

    assert caught.value.status_code == 503
    assert caught.value.code == invoice_client.INVOICE_SERVICE_UNAVAILABLE
    assert "sensitive" not in caught.value.message


@pytest.mark.parametrize(
    "failure",
    [
        httpx.ConnectError("connection refused"),
        httpx.ReadTimeout("timed out"),
    ],
)
def test_invoice_client_maps_transport_failures_to_503(
    monkeypatch: pytest.MonkeyPatch,
    failure: httpx.RequestError,
) -> None:
    monkeypatch.setenv("INVOICE_API_BASE_URL", "http://invoice.test:8002")
    monkeypatch.setenv("INVOICE_INTEGRATION_TOKEN", INVOICE_TOKEN)

    def handler(request: httpx.Request) -> httpx.Response:
        failure.request = request
        raise failure

    _mock_httpx_client(monkeypatch, handler)

    with pytest.raises(invoice_client.InvoiceIntegrationError) as caught:
        invoice_client.fetch_invoice_statuses("PR-10")

    assert caught.value.status_code == 503
    assert caught.value.code == invoice_client.INVOICE_SERVICE_UNAVAILABLE


@pytest.mark.parametrize(
    ("base_url", "token"),
    [
        ("", INVOICE_TOKEN),
        ("not-a-url", INVOICE_TOKEN),
        ("http://invoice.test:8002", ""),
        ("http://invoice.test:8002", "  "),
    ],
)
def test_invoice_client_fails_safely_when_configuration_is_invalid(
    monkeypatch: pytest.MonkeyPatch,
    base_url: str,
    token: str,
) -> None:
    monkeypatch.setenv("INVOICE_API_BASE_URL", base_url)
    monkeypatch.setenv("INVOICE_INTEGRATION_TOKEN", token)

    with pytest.raises(invoice_client.InvoiceIntegrationError) as caught:
        invoice_client.fetch_invoice_statuses("PR-10")

    assert caught.value.status_code == 502
    assert caught.value.code == invoice_client.INVOICE_SERVICE_ERROR


@pytest.mark.parametrize(
    ("failure", "status_code", "code"),
    [
        (
            invoice_client.InvoiceIntegrationError(
                status_code=502,
                code=invoice_client.INVOICE_SERVICE_ERROR,
                message="Invoice information could not be retrieved.",
            ),
            502,
            invoice_client.INVOICE_SERVICE_ERROR,
        ),
        (
            invoice_client.InvoiceIntegrationError(
                status_code=503,
                code=invoice_client.INVOICE_SERVICE_UNAVAILABLE,
                message="Invoice information is temporarily unavailable.",
            ),
            503,
            invoice_client.INVOICE_SERVICE_UNAVAILABLE,
        ),
    ],
)
def test_proxy_returns_stable_safe_dependency_errors(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
    failure: invoice_client.InvoiceIntegrationError,
    status_code: int,
    code: str,
) -> None:
    def fail(_: str) -> list[InvoiceStatusOut]:
        raise failure

    monkeypatch.setattr(
        "app.routers.purchase_requests.fetch_invoice_statuses",
        fail,
    )

    response = client.get(
        "/purchase-request/10/invoices",
        headers=_auth_headers(OWNER_TOKEN),
    )

    assert response.status_code == status_code
    assert response.json() == {"code": code, "message": failure.message}
