from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from app.db import Base, get_db
from app.main import app
from app.models import PurchaseRequest
from app.security import INTEGRATION_TOKEN_HEADER

INTEGRATION_TOKEN = "test-pr-integration-token"
AUTH_HEADERS = {INTEGRATION_TOKEN_HEADER: INTEGRATION_TOKEN}
REFERENCE_FIELDS = {
    "request_code",
    "request_name",
    "request_author",
    "supplier_name",
    "request_approval_status",
}


def _purchase_request(
    *,
    id: int,
    code: str,
    approval_status: str,
) -> PurchaseRequest:
    return PurchaseRequest(
        id=id,
        request_author=f"requester-{id}",
        request_name=f"Request {id}",
        request_code=code,
        supplier_name=f"Supplier {id}",
        supplier_email=f"supplier-{id}@example.com",
        request_details=f"Details for request {id}",
        request_approval_status=approval_status,
    )


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
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
                _purchase_request(id=30, code="PR-30", approval_status="approved"),
                _purchase_request(id=10, code="PR-10", approval_status="approved"),
                _purchase_request(
                    id=20,
                    code="PR-20",
                    approval_status="sent for approval",
                ),
                _purchase_request(id=40, code="PR-40", approval_status="rejected"),
            ]
        )
        db.commit()

    def override_get_db() -> Iterator[Session]:
        with testing_session() as db:
            yield db

    monkeypatch.setenv("PR_INTEGRATION_TOKEN", INTEGRATION_TOKEN)
    app.dependency_overrides[get_db] = override_get_db
    with TestClient(app) as test_client:
        yield test_client

    app.dependency_overrides.clear()
    Base.metadata.drop_all(engine)
    engine.dispose()


@pytest.mark.parametrize(
    "headers",
    [
        {},
        {INTEGRATION_TOKEN_HEADER: ""},
        {INTEGRATION_TOKEN_HEADER: "wrong-token"},
        {INTEGRATION_TOKEN_HEADER: b"wrong-tok\xe9"},
    ],
)
def test_integration_token_is_required(
    client: TestClient,
    headers: dict[str, str | bytes],
) -> None:
    response = client.get(
        "/integration/purchase-requests",
        params={"status": "approved"},
        headers=headers,
    )

    assert response.status_code == 401
    assert response.json() == {"detail": "Invalid integration token"}


def test_integration_api_fails_closed_without_configured_token(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("PR_INTEGRATION_TOKEN")

    response = client.get(
        "/integration/purchase-requests",
        params={"status": "approved"},
        headers=AUTH_HEADERS,
    )

    assert response.status_code == 401
    assert response.json() == {"detail": "Invalid integration token"}


def test_candidates_return_only_approved_minimal_references(
    client: TestClient,
) -> None:
    response = client.get(
        "/integration/purchase-requests",
        params={"status": "approved"},
        headers=AUTH_HEADERS,
    )

    assert response.status_code == 200
    body = response.json()
    assert [item["request_code"] for item in body] == ["PR-10", "PR-30"]
    assert all(item["request_approval_status"] == "approved" for item in body)
    assert all(set(item) == REFERENCE_FIELDS for item in body)


@pytest.mark.parametrize(
    ("request_code", "approval_status"),
    [
        ("PR-10", "approved"),
        ("PR-20", "sent for approval"),
        ("PR-40", "rejected"),
    ],
)
def test_exact_lookup_returns_any_existing_status(
    client: TestClient,
    request_code: str,
    approval_status: str,
) -> None:
    response = client.get(
        f"/integration/purchase-requests/{request_code}",
        headers=AUTH_HEADERS,
    )

    assert response.status_code == 200
    assert response.json()["request_code"] == request_code
    assert response.json()["request_approval_status"] == approval_status
    assert set(response.json()) == REFERENCE_FIELDS


def test_exact_lookup_returns_not_found_for_absent_code(
    client: TestClient,
) -> None:
    response = client.get(
        "/integration/purchase-requests/PR-999",
        headers=AUTH_HEADERS,
    )

    assert response.status_code == 404
    assert response.json() == {"detail": "Purchase request not found"}
