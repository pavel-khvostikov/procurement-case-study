package com.casestudy.invoiceapp.invoice.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Used by the list endpoint and as the response body for create/update.
 * Does NOT include the attachment bytes — those go through a dedicated
 * download endpoint.
 *
 * Field names are camelCase in Java; Jackson serialises them as snake_case
 * via the global property naming strategy in application.yml, so the JSON
 * over the wire matches what the PR backend emits.
 */
public class InvoiceSummaryDto {
    public final Long id;
    public final String invoiceNumber;
    public final String supplier;
    public final String purchaseRequestNumber;
    public final Instant purchaseRequestValidatedAt;
    public final BigDecimal invoiceSum;
    public final BigDecimal invoiceSumPaid;
    public final String invoiceStatus;
    public final String attachmentFilename;
    public final String uploadedBy;
    public final Instant createdAt;
    public final Instant updatedAt;

    public InvoiceSummaryDto(Long id,
                             String invoiceNumber,
                             String supplier,
                             String purchaseRequestNumber,
                             Instant purchaseRequestValidatedAt,
                             BigDecimal invoiceSum,
                             BigDecimal invoiceSumPaid,
                             String invoiceStatus,
                             String attachmentFilename,
                             String uploadedBy,
                             Instant createdAt,
                             Instant updatedAt) {
        this.id = id;
        this.invoiceNumber = invoiceNumber;
        this.supplier = supplier;
        this.purchaseRequestNumber = purchaseRequestNumber;
        this.purchaseRequestValidatedAt = purchaseRequestValidatedAt;
        this.invoiceSum = invoiceSum;
        this.invoiceSumPaid = invoiceSumPaid;
        this.invoiceStatus = invoiceStatus;
        this.attachmentFilename = attachmentFilename;
        this.uploadedBy = uploadedBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
