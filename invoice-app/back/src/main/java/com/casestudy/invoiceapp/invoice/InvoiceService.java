package com.casestudy.invoiceapp.invoice;

import com.casestudy.invoiceapp.invoice.dto.InvoiceSummaryDto;
import com.casestudy.invoiceapp.invoice.dto.InvoiceUpdateDto;
import com.casestudy.invoiceapp.purchaserequest.PurchaseRequestReference;
import com.casestudy.invoiceapp.purchaserequest.PurchaseRequestRelationshipService;
import com.casestudy.invoiceapp.purchaserequest.PurchaseRequestRelationshipService.ValidatedRelationship;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class InvoiceService {

    private static final Set<String> VALID_STATUSES = Set.of("created", "prepaid", "paid");

    private final InvoiceRepository invoices;
    private final PurchaseRequestRelationshipService relationships;

    public InvoiceService(
            InvoiceRepository invoices,
            PurchaseRequestRelationshipService relationships
    ) {
        this.invoices = invoices;
        this.relationships = relationships;
    }

    public List<InvoiceSummaryDto> list(String purchaseRequestNumber) {
        if (purchaseRequestNumber == null) {
            return invoices.findAllSummaries();
        }
        return invoices.findValidatedSummariesByPurchaseRequestNumber(purchaseRequestNumber);
    }

    public InvoiceSummaryDto create(CreateInvoiceCommand command) {
        requireValidStatus(command.invoiceStatus());
        ValidatedRelationship relationship =
                relationships.validateForCreate(command.purchaseRequestNumber());

        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(command.invoiceNumber());
        invoice.setSupplier(command.supplier());
        invoice.setPurchaseRequestNumber(relationship.requestCode());
        invoice.setPurchaseRequestValidatedAt(relationship.validatedAt());
        invoice.setInvoiceSum(command.invoiceSum());
        invoice.setInvoiceSumPaid(
                command.invoiceSumPaid() == null ? BigDecimal.ZERO : command.invoiceSumPaid()
        );
        invoice.setInvoiceStatus(command.invoiceStatus());
        invoice.setUploadedBy(command.uploadedBy());
        invoice.setAttachmentBytes(command.attachmentBytes());
        invoice.setAttachmentFilename(command.attachmentFilename());
        invoice.setAttachmentContentType(command.attachmentContentType());

        return toSummary(invoices.save(invoice));
    }

    public InvoiceSummaryDto update(Long id, InvoiceUpdateDto update) {
        Invoice invoice = invoices.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));

        if (update.invoiceStatus != null) {
            requireValidStatus(update.invoiceStatus);
        }

        /*
         * Resolve the relationship before invoking any entity setter. A remote
         * failure therefore leaves every submitted invoice field untouched.
         */
        Optional<ValidatedRelationship> relationship =
                relationships.validateForUpdate(invoice, update);

        if (update.invoiceNumber != null) {
            invoice.setInvoiceNumber(update.invoiceNumber);
        }
        if (update.supplier != null) {
            invoice.setSupplier(update.supplier);
        }
        relationship.ifPresent(validated -> {
            invoice.setPurchaseRequestNumber(validated.requestCode());
            invoice.setPurchaseRequestValidatedAt(validated.validatedAt());
        });
        if (update.invoiceSum != null) {
            invoice.setInvoiceSum(update.invoiceSum);
        }
        if (update.invoiceSumPaid != null) {
            invoice.setInvoiceSumPaid(update.invoiceSumPaid);
        }
        if (update.invoiceStatus != null) {
            invoice.setInvoiceStatus(update.invoiceStatus);
        }

        return toSummary(invoices.save(invoice));
    }

    public List<PurchaseRequestReference> approvedPurchaseRequests() {
        return relationships.approvedCandidates();
    }

    private static void requireValidStatus(String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid status — expected one of: created, prepaid, paid"
            );
        }
    }

    private static InvoiceSummaryDto toSummary(Invoice invoice) {
        return new InvoiceSummaryDto(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getSupplier(),
                invoice.getPurchaseRequestNumber(),
                invoice.getPurchaseRequestValidatedAt(),
                invoice.getInvoiceSum(),
                invoice.getInvoiceSumPaid(),
                invoice.getInvoiceStatus(),
                invoice.getAttachmentFilename(),
                invoice.getUploadedBy(),
                invoice.getCreatedAt(),
                invoice.getUpdatedAt()
        );
    }

    public record CreateInvoiceCommand(
            String invoiceNumber,
            String supplier,
            String purchaseRequestNumber,
            BigDecimal invoiceSum,
            BigDecimal invoiceSumPaid,
            String invoiceStatus,
            byte[] attachmentBytes,
            String attachmentFilename,
            String attachmentContentType,
            String uploadedBy
    ) {
    }
}
