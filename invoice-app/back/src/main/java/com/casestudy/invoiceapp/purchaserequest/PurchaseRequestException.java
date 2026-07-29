package com.casestudy.invoiceapp.purchaserequest;

import org.springframework.http.HttpStatus;

public class PurchaseRequestException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private PurchaseRequestException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static PurchaseRequestException required() {
        return new PurchaseRequestException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "PURCHASE_REQUEST_REQUIRED",
                "A purchase request is required."
        );
    }

    public static PurchaseRequestException notFound() {
        return new PurchaseRequestException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "PURCHASE_REQUEST_NOT_FOUND",
                "Purchase request was not found."
        );
    }

    public static PurchaseRequestException notApproved() {
        return new PurchaseRequestException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "PURCHASE_REQUEST_NOT_APPROVED",
                "Purchase request must be approved."
        );
    }

    public static PurchaseRequestException unavailable() {
        return new PurchaseRequestException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PURCHASE_REQUEST_SERVICE_UNAVAILABLE",
                "Purchase request service is temporarily unavailable."
        );
    }

    public static PurchaseRequestException serviceError() {
        return new PurchaseRequestException(
                HttpStatus.BAD_GATEWAY,
                "PURCHASE_REQUEST_SERVICE_ERROR",
                "Purchase request service returned an unexpected response."
        );
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
