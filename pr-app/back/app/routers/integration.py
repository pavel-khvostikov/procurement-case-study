"""Narrow service-facing purchase request read contract."""
from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import PurchaseRequest
from ..schemas import PurchaseRequestReferenceOut
from ..security import require_integration_token

router = APIRouter(
    prefix="/integration/purchase-requests",
    tags=["integration"],
    dependencies=[Depends(require_integration_token)],
)


@router.get("", response_model=list[PurchaseRequestReferenceOut])
def list_approved_purchase_requests(
    approval_status: Literal["approved"] = Query(alias="status"),
    db: Session = Depends(get_db),
) -> list[PurchaseRequest]:
    return (
        db.query(PurchaseRequest)
        .filter(PurchaseRequest.request_approval_status == approval_status)
        .order_by(PurchaseRequest.id.asc())
        .all()
    )


@router.get("/{request_code}", response_model=PurchaseRequestReferenceOut)
def get_purchase_request_reference(
    request_code: str,
    db: Session = Depends(get_db),
) -> PurchaseRequest:
    purchase_request = (
        db.query(PurchaseRequest)
        .filter(PurchaseRequest.request_code == request_code)
        .one_or_none()
    )
    if purchase_request is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Purchase request not found",
        )
    return purchase_request
