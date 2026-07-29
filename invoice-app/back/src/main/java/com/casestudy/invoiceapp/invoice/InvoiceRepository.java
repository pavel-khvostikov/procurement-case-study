package com.casestudy.invoiceapp.invoice;

import com.casestudy.invoiceapp.invoice.dto.InvoiceIntegrationDto;
import com.casestudy.invoiceapp.invoice.dto.InvoiceSummaryDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    /**
     * Projection query that doesn't touch the attachment_bytes column —
     * keeps the list response small even when invoices have multi-MB PDFs.
     */
    @Query("""
        select new com.casestudy.invoiceapp.invoice.dto.InvoiceSummaryDto(
            i.id, i.invoiceNumber, i.supplier, i.purchaseRequestNumber,
            i.purchaseRequestValidatedAt,
            i.invoiceSum, i.invoiceSumPaid, i.invoiceStatus,
            i.attachmentFilename, i.uploadedBy, i.createdAt, i.updatedAt
        )
        from Invoice i
        order by i.id desc
    """)
    List<InvoiceSummaryDto> findAllSummaries();

    /**
     * A matching legacy string is not a relationship. Only rows with
     * validation provenance participate in exact PR filtering.
     */
    @Query("""
        select new com.casestudy.invoiceapp.invoice.dto.InvoiceSummaryDto(
            i.id, i.invoiceNumber, i.supplier, i.purchaseRequestNumber,
            i.purchaseRequestValidatedAt,
            i.invoiceSum, i.invoiceSumPaid, i.invoiceStatus,
            i.attachmentFilename, i.uploadedBy, i.createdAt, i.updatedAt
        )
        from Invoice i
        where i.purchaseRequestNumber = :purchaseRequestNumber
          and i.purchaseRequestValidatedAt is not null
        order by i.id desc
    """)
    List<InvoiceSummaryDto> findValidatedSummariesByPurchaseRequestNumber(String purchaseRequestNumber);

    /**
     * Service-facing relationship query. Keep this projection deliberately
     * narrower than the finance-facing summary.
     */
    @Query("""
        select new com.casestudy.invoiceapp.invoice.dto.InvoiceIntegrationDto(
            i.id, i.invoiceNumber, i.invoiceStatus
        )
        from Invoice i
        where i.purchaseRequestNumber = :purchaseRequestNumber
          and i.purchaseRequestValidatedAt is not null
        order by i.id desc
    """)
    List<InvoiceIntegrationDto> findIntegrationInvoicesByPurchaseRequestNumber(
            String purchaseRequestNumber
    );
}
