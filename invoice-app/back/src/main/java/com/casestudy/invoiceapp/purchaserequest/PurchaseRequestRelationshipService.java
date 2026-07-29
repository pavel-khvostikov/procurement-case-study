package com.casestudy.invoiceapp.purchaserequest;

import com.casestudy.invoiceapp.invoice.Invoice;
import com.casestudy.invoiceapp.invoice.dto.InvoiceUpdateDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The single Invoice-side authority for creating or changing a PR link.
 */
@Service
public class PurchaseRequestRelationshipService {

    private final PurchaseRequestClient client;

    public PurchaseRequestRelationshipService(PurchaseRequestClient client) {
        this.client = client;
    }

    public ValidatedRelationship validateForCreate(String submittedCode) {
        return validateRequired(submittedCode);
    }

    public Optional<ValidatedRelationship> validateForUpdate(
            Invoice invoice,
            InvoiceUpdateDto update
    ) {
        if (!update.isPurchaseRequestNumberPresent()) {
            return Optional.empty();
        }

        String submittedCode = normalizeRequired(update.getPurchaseRequestNumber());
        if (invoice.getPurchaseRequestValidatedAt() != null
                && submittedCode.equals(invoice.getPurchaseRequestNumber())) {
            return Optional.empty();
        }

        return Optional.of(validateRequired(submittedCode));
    }

    public List<PurchaseRequestReference> approvedCandidates() {
        return client.findApproved();
    }

    private ValidatedRelationship validateRequired(String submittedCode) {
        String normalizedCode = normalizeRequired(submittedCode);
        PurchaseRequestReference reference = client.findByCode(normalizedCode)
                .orElseThrow(PurchaseRequestException::notFound);

        if (!"approved".equals(reference.requestApprovalStatus())) {
            throw PurchaseRequestException.notApproved();
        }

        return new ValidatedRelationship(reference.requestCode(), Instant.now());
    }

    private static String normalizeRequired(String submittedCode) {
        if (submittedCode == null || submittedCode.isBlank()) {
            throw PurchaseRequestException.required();
        }
        return submittedCode.trim();
    }

    public record ValidatedRelationship(String requestCode, Instant validatedAt) {
    }
}
