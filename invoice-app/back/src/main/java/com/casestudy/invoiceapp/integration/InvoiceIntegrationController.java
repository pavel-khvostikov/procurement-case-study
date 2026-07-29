package com.casestudy.invoiceapp.integration;

import com.casestudy.invoiceapp.invoice.InvoiceRepository;
import com.casestudy.invoiceapp.invoice.dto.InvoiceIntegrationDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/integration/invoices")
public class InvoiceIntegrationController {

    private final InvoiceRepository invoices;
    private final InvoiceIntegrationTokenAuthenticator authenticator;

    public InvoiceIntegrationController(
            InvoiceRepository invoices,
            InvoiceIntegrationTokenAuthenticator authenticator
    ) {
        this.invoices = invoices;
        this.authenticator = authenticator;
    }

    @GetMapping
    public List<InvoiceIntegrationDto> list(
            @RequestHeader(
                    value = InvoiceIntegrationTokenAuthenticator.HEADER_NAME,
                    required = false
            ) String integrationToken,
            @RequestParam(value = "purchase_request_number", required = false)
            String purchaseRequestNumber
    ) {
        authenticator.requireValid(integrationToken);
        if (purchaseRequestNumber == null || purchaseRequestNumber.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "purchase_request_number is required"
            );
        }
        return invoices.findIntegrationInvoicesByPurchaseRequestNumber(
                purchaseRequestNumber
        );
    }
}
