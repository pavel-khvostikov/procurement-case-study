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
        BigDecimal paidAmount = command.invoiceSumPaid() == null
                ? BigDecimal.ZERO
                : command.invoiceSumPaid();
        validatePaymentState(command.invoiceSum(), paidAmount, command.invoiceStatus());
        ValidatedRelationship relationship =
                relationships.validateForCreate(command.purchaseRequestNumber());

        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(command.invoiceNumber());
        invoice.setSupplier(command.supplier());
        invoice.setPurchaseRequestNumber(relationship.requestCode());
        invoice.setPurchaseRequestValidatedAt(relationship.validatedAt());
        invoice.setInvoiceSum(command.invoiceSum());
        invoice.setInvoiceSumPaid(paidAmount);
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

        if (updatesPaymentState(update)) {
            validatePaymentState(
                    update.invoiceSum == null ? invoice.getInvoiceSum() : update.invoiceSum,
                    update.invoiceSumPaid == null
                            ? invoice.getInvoiceSumPaid()
                            : update.invoiceSumPaid,
                    update.invoiceStatus == null
                            ? invoice.getInvoiceStatus()
                            : update.invoiceStatus
            );
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

    private static boolean updatesPaymentState(InvoiceUpdateDto update) {
        return update.invoiceSum != null
                || update.invoiceSumPaid != null
                || update.invoiceStatus != null;
    }

    private static void validatePaymentState(
            BigDecimal invoiceSum,
            BigDecimal invoiceSumPaid,
            String status
    ) {
        if (!VALID_STATUSES.contains(status)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid status — expected one of: created, prepaid, paid"
            );
        }

        if (invoiceSum == null || invoiceSum.signum() <= 0) {
            throw invalidPaymentState("Invoice sum must be greater than zero");
        }
        if (invoiceSumPaid == null
                || invoiceSumPaid.signum() < 0
                || invoiceSumPaid.compareTo(invoiceSum) > 0) {
            throw invalidPaymentState(
                    "Paid amount must be between zero and the invoice sum"
            );
        }
        if (!hasCentPrecision(invoiceSum) || !hasCentPrecision(invoiceSumPaid)) {
            throw invalidPaymentState("Invoice amounts support at most two decimal places");
        }

        int paidComparedToZero = invoiceSumPaid.compareTo(BigDecimal.ZERO);
        int paidComparedToTotal = invoiceSumPaid.compareTo(invoiceSum);
        boolean consistent = switch (status) {
            case "created" -> paidComparedToZero == 0;
            case "prepaid" -> paidComparedToZero > 0 && paidComparedToTotal < 0;
            case "paid" -> paidComparedToTotal == 0;
            default -> false;
        };
        if (!consistent) {
            throw invalidPaymentState(
                    "Payment status must match the paid amount: created means zero, "
                            + "prepaid means a partial payment, and paid means the full invoice sum"
            );
        }
    }

    private static boolean hasCentPrecision(BigDecimal amount) {
        return amount.stripTrailingZeros().scale() <= 2;
    }

    private static ResponseStatusException invalidPaymentState(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
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
