package com.casestudy.invoiceapp.purchaserequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PurchaseRequestExceptionHandler {

    @ExceptionHandler(PurchaseRequestException.class)
    public ResponseEntity<ErrorResponse> handle(PurchaseRequestException exception) {
        return ResponseEntity
                .status(exception.getStatus())
                .body(new ErrorResponse(exception.getCode(), exception.getMessage()));
    }

    public record ErrorResponse(String code, String message) {
    }
}
