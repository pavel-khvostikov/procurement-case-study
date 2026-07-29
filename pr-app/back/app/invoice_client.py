"""Narrow client for requester-visible invoice statuses."""
from __future__ import annotations

import os

import httpx
from pydantic import TypeAdapter, ValidationError

from .schemas import InvoiceStatusOut
from .security import INTEGRATION_TOKEN_HEADER

INVOICE_SERVICE_UNAVAILABLE = "INVOICE_SERVICE_UNAVAILABLE"
INVOICE_SERVICE_ERROR = "INVOICE_SERVICE_ERROR"

_INVOICE_LIST = TypeAdapter(list[InvoiceStatusOut])


class InvoiceIntegrationError(Exception):
    """Safe, stable error returned when the Invoice dependency fails."""

    def __init__(self, *, status_code: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message


def _service_unavailable() -> InvoiceIntegrationError:
    return InvoiceIntegrationError(
        status_code=503,
        code=INVOICE_SERVICE_UNAVAILABLE,
        message="Invoice information is temporarily unavailable.",
    )


def _service_error() -> InvoiceIntegrationError:
    return InvoiceIntegrationError(
        status_code=502,
        code=INVOICE_SERVICE_ERROR,
        message="Invoice information could not be retrieved.",
    )


def _configuration() -> tuple[str, str]:
    base_url = os.environ.get("INVOICE_API_BASE_URL", "").strip()
    token = os.environ.get("INVOICE_INTEGRATION_TOKEN", "")
    if not base_url or not token or token.isspace():
        raise _service_error()

    try:
        parsed_url = httpx.URL(base_url)
    except (TypeError, ValueError):
        raise _service_error() from None
    if parsed_url.scheme not in {"http", "https"} or not parsed_url.host:
        raise _service_error()

    return f"{base_url.rstrip('/')}/", token


def fetch_invoice_statuses(request_code: str) -> list[InvoiceStatusOut]:
    """Fetch validated invoice summaries for one canonical PR code."""
    base_url, token = _configuration()
    timeout = httpx.Timeout(3.0, connect=2.0)

    try:
        with httpx.Client(
            base_url=base_url,
            headers={INTEGRATION_TOKEN_HEADER: token},
            timeout=timeout,
        ) as client:
            response = client.get(
                "integration/invoices",
                params={"purchase_request_number": request_code},
            )
    except httpx.RequestError:
        raise _service_unavailable() from None
    except (TypeError, ValueError):
        raise _service_error() from None

    if response.status_code >= 500:
        raise _service_unavailable()
    if response.status_code != 200:
        raise _service_error()

    try:
        return _INVOICE_LIST.validate_json(response.content, strict=True)
    except ValidationError:
        raise _service_error() from None
