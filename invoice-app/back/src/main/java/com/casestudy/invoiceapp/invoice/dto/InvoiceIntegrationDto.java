package com.casestudy.invoiceapp.invoice.dto;

/**
 * Minimal service-facing view of an invoice linked to a purchase request.
 *
 * <p>Amounts, supplier data, attachments, and validation provenance stay
 * inside the Invoice application.
 */
public class InvoiceIntegrationDto {
    public final Long id;
    public final String invoiceNumber;
    public final String invoiceStatus;

    public InvoiceIntegrationDto(Long id, String invoiceNumber, String invoiceStatus) {
        this.id = id;
        this.invoiceNumber = invoiceNumber;
        this.invoiceStatus = invoiceStatus;
    }
}
