package com.casestudy.invoiceapp.invoice.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.math.BigDecimal;

/**
 * PUT /invoice/{id} body. Every field is optional — only the ones the client
 * sends get applied. Attachment replacement is not supported by this endpoint.
 */
public class InvoiceUpdateDto {
    public String invoiceNumber;
    public String supplier;
    public BigDecimal invoiceSum;
    public BigDecimal invoiceSumPaid;
    public String invoiceStatus;

    private String purchaseRequestNumber;
    private boolean purchaseRequestNumberPresent;

    public String getPurchaseRequestNumber() {
        return purchaseRequestNumber;
    }

    /**
     * Tracks JSON-field presence so an omitted relationship can be
     * distinguished from an explicit null, which is an attempted clear.
     */
    @JsonSetter("purchase_request_number")
    public void setPurchaseRequestNumber(String purchaseRequestNumber) {
        this.purchaseRequestNumberPresent = true;
        this.purchaseRequestNumber = purchaseRequestNumber;
    }

    @JsonIgnore
    public boolean isPurchaseRequestNumberPresent() {
        return purchaseRequestNumberPresent;
    }
}
