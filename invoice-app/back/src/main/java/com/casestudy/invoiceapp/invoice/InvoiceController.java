package com.casestudy.invoiceapp.invoice;

import com.casestudy.invoiceapp.auth.CurrentUserFilter;
import com.casestudy.invoiceapp.invoice.dto.InvoiceSummaryDto;
import com.casestudy.invoiceapp.invoice.dto.InvoiceUpdateDto;
import com.casestudy.invoiceapp.purchaserequest.PurchaseRequestReference;
import com.casestudy.invoiceapp.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/invoice")
public class InvoiceController {

    private final InvoiceRepository invoices;
    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceRepository invoices, InvoiceService invoiceService) {
        this.invoices = invoices;
        this.invoiceService = invoiceService;
    }

    @GetMapping
    public List<InvoiceSummaryDto> list(
            HttpServletRequest req,
            @RequestParam(value = "purchase_request_number", required = false)
            String purchaseRequestNumber
    ) {
        requireFinance(req);
        return invoiceService.list(purchaseRequestNumber);
    }

    @GetMapping("/purchase-requests")
    public List<PurchaseRequestReference> purchaseRequests(HttpServletRequest req) {
        requireFinance(req);
        return invoiceService.approvedPurchaseRequests();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<InvoiceSummaryDto> create(
            HttpServletRequest req,
            @RequestParam("invoice_number") String invoiceNumber,
            @RequestParam("supplier") String supplier,
            @RequestParam(value = "purchase_request_number", required = false) String purchaseRequestNumber,
            @RequestParam("invoice_sum") BigDecimal invoiceSum,
            @RequestParam(value = "invoice_sum_paid", required = false) BigDecimal invoiceSumPaid,
            @RequestParam(value = "invoice_status", defaultValue = "created") String invoiceStatus,
            @RequestParam(value = "attachment", required = false) MultipartFile attachment
    ) throws IOException {
        User user = requireFinance(req);
        byte[] attachmentBytes = null;
        String attachmentFilename = null;
        String attachmentContentType = null;
        if (attachment != null && !attachment.isEmpty()) {
            attachmentBytes = attachment.getBytes();
            attachmentFilename = attachment.getOriginalFilename();
            attachmentContentType = attachment.getContentType();
        }

        InvoiceSummaryDto created = invoiceService.create(new InvoiceService.CreateInvoiceCommand(
                invoiceNumber,
                supplier,
                purchaseRequestNumber,
                invoiceSum,
                invoiceSumPaid,
                invoiceStatus,
                attachmentBytes,
                attachmentFilename,
                attachmentContentType,
                user.getUsername()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InvoiceSummaryDto update(HttpServletRequest req,
                                    @PathVariable Long id,
                                    @RequestBody InvoiceUpdateDto body) {
        requireFinance(req);
        return invoiceService.update(id, body);
    }

    @GetMapping("/{id}/attachment")
    public ResponseEntity<byte[]> download(HttpServletRequest req, @PathVariable Long id) {
        requireFinance(req);
        Invoice inv = invoices.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (inv.getAttachmentBytes() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No attachment");
        }
        String filename = inv.getAttachmentFilename() != null ? inv.getAttachmentFilename() : "invoice.pdf";
        String contentType = inv.getAttachmentContentType() != null ? inv.getAttachmentContentType() : MediaType.APPLICATION_PDF_VALUE;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(inv.getAttachmentBytes());
    }

    // ---------- helpers ----------

    private static User requireFinance(HttpServletRequest req) {
        User user = CurrentUserFilter.currentUser(req);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        if (!"finance".equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requires role: finance");
        }
        return user;
    }

}
