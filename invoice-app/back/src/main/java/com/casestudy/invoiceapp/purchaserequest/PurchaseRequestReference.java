package com.casestudy.invoiceapp.purchaserequest;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Minimal service contract shared with the Purchase Request backend.
 */
public record PurchaseRequestReference(
        @JsonProperty("request_code") String requestCode,
        @JsonProperty("request_name") String requestName,
        @JsonProperty("request_author") String requestAuthor,
        @JsonProperty("supplier_name") String supplierName,
        @JsonProperty("request_approval_status") String requestApprovalStatus
) {
}
