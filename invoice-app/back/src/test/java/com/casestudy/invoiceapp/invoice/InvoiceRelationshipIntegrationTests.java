package com.casestudy.invoiceapp.invoice;

import com.casestudy.invoiceapp.auth.CurrentUserFilter;
import com.casestudy.invoiceapp.purchaserequest.PurchaseRequestClient;
import com.casestudy.invoiceapp.purchaserequest.PurchaseRequestException;
import com.casestudy.invoiceapp.purchaserequest.PurchaseRequestReference;
import com.casestudy.invoiceapp.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:invoice-relationship;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "purchase-request.integration-token=test-token"
})
class InvoiceRelationshipIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InvoiceRepository invoices;

    @MockBean
    private PurchaseRequestClient purchaseRequests;

    @BeforeEach
    void resetState() {
        invoices.deleteAll();
        reset(purchaseRequests);
    }

    @Test
    void approvedPurchaseRequestCreationStoresCanonicalCodeAndProvenance() throws Exception {
        when(purchaseRequests.findByCode("PR-2"))
                .thenReturn(Optional.of(reference("PR-2", "approved")));

        mockMvc.perform(multipart("/invoice")
                        .with(finance())
                        .param("invoice_number", "INV-100")
                        .param("supplier", "Atlassian")
                        .param("purchase_request_number", " PR-2 ")
                        .param("invoice_sum", "125.50")
                        .param("invoice_status", "created"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purchase_request_number").value("PR-2"))
                .andExpect(jsonPath("$.purchase_request_validated_at").isNotEmpty());

        assertThat(invoices.findAll()).singleElement().satisfies(invoice -> {
            assertThat(invoice.getPurchaseRequestNumber()).isEqualTo("PR-2");
            assertThat(invoice.getPurchaseRequestValidatedAt()).isNotNull();
        });
        verify(purchaseRequests).findByCode("PR-2");
    }

    @Test
    void missingPurchaseRequestIsRejectedWithoutPersistence() throws Exception {
        mockMvc.perform(multipart("/invoice")
                        .with(finance())
                        .param("invoice_number", "INV-100")
                        .param("supplier", "Atlassian")
                        .param("invoice_sum", "125.50"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PURCHASE_REQUEST_REQUIRED"))
                .andExpect(jsonPath("$.message").value("A purchase request is required."));

        assertThat(invoices.count()).isZero();
        verifyNoInteractions(purchaseRequests);
    }

    @Test
    void fabricatedPurchaseRequestIsRejectedWithoutPersistence() throws Exception {
        when(purchaseRequests.findByCode("PR-404")).thenReturn(Optional.empty());

        createInvoice("PR-404")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PURCHASE_REQUEST_NOT_FOUND"));

        assertThat(invoices.count()).isZero();
    }

    @Test
    void nonApprovedPurchaseRequestIsRejectedWithoutPersistence() throws Exception {
        when(purchaseRequests.findByCode("PR-1"))
                .thenReturn(Optional.of(reference("PR-1", "sent for approval")));

        createInvoice("PR-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PURCHASE_REQUEST_NOT_APPROVED"));

        assertThat(invoices.count()).isZero();
    }

    @Test
    void failedRelationshipChangeLeavesEveryInvoiceFieldUnchanged() throws Exception {
        Invoice original = invoice("INV-1", "Old supplier", "PR-1", Instant.parse("2026-01-01T00:00:00Z"));
        original.setInvoiceSum(new BigDecimal("100.00"));
        original.setInvoiceSumPaid(new BigDecimal("10.00"));
        original.setInvoiceStatus("created");
        original = invoices.save(original);
        Instant originalUpdatedAt = invoices.findById(original.getId())
                .orElseThrow()
                .getUpdatedAt();

        when(purchaseRequests.findByCode("PR-2"))
                .thenReturn(Optional.of(reference("PR-2", "rejected")));

        mockMvc.perform(put("/invoice/{id}", original.getId())
                        .with(finance())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "invoice_number": "INV-CHANGED",
                                  "supplier": "New supplier",
                                  "purchase_request_number": "PR-2",
                                  "invoice_sum": 999.00,
                                  "invoice_sum_paid": 999.00,
                                  "invoice_status": "paid"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PURCHASE_REQUEST_NOT_APPROVED"));

        Invoice unchanged = invoices.findById(original.getId()).orElseThrow();
        assertThat(unchanged.getInvoiceNumber()).isEqualTo("INV-1");
        assertThat(unchanged.getSupplier()).isEqualTo("Old supplier");
        assertThat(unchanged.getPurchaseRequestNumber()).isEqualTo("PR-1");
        assertThat(unchanged.getPurchaseRequestValidatedAt())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(unchanged.getInvoiceSum()).isEqualByComparingTo("100.00");
        assertThat(unchanged.getInvoiceSumPaid()).isEqualByComparingTo("10.00");
        assertThat(unchanged.getInvoiceStatus()).isEqualTo("created");
        assertThat(unchanged.getUpdatedAt()).isEqualTo(originalUpdatedAt);
    }

    @Test
    void explicitSelectionOfSameLegacyCodeAddsProvenance() throws Exception {
        Invoice legacy = invoices.save(invoice("INV-1", "Atlassian", "PR-2", null));
        when(purchaseRequests.findByCode("PR-2"))
                .thenReturn(Optional.of(reference("PR-2", "approved")));

        mockMvc.perform(put("/invoice/{id}", legacy.getId())
                        .with(finance())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchase_request_number": "PR-2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchase_request_number").value("PR-2"))
                .andExpect(jsonPath("$.purchase_request_validated_at").isNotEmpty());

        assertThat(invoices.findById(legacy.getId()).orElseThrow().getPurchaseRequestValidatedAt())
                .isNotNull();
        verify(purchaseRequests).findByCode("PR-2");
    }

    @Test
    void unchangedValidatedCodeDoesNotCallPurchaseRequestService() throws Exception {
        Invoice invoice = invoices.save(invoice(
                "INV-1",
                "Atlassian",
                "PR-2",
                Instant.parse("2026-01-01T00:00:00Z")
        ));

        mockMvc.perform(put("/invoice/{id}", invoice.getId())
                        .with(finance())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "supplier": "Atlassian Serbia",
                                  "purchase_request_number": " PR-2 "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplier").value("Atlassian Serbia"))
                .andExpect(jsonPath("$.purchase_request_validated_at").isNotEmpty());

        verifyNoInteractions(purchaseRequests);
    }

    @Test
    void explicitNullCannotClearValidatedRelationship() throws Exception {
        Invoice invoice = invoices.save(invoice(
                "INV-1",
                "Atlassian",
                "PR-2",
                Instant.parse("2026-01-01T00:00:00Z")
        ));

        mockMvc.perform(put("/invoice/{id}", invoice.getId())
                        .with(finance())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchase_request_number": null}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PURCHASE_REQUEST_REQUIRED"));

        Invoice unchanged = invoices.findById(invoice.getId()).orElseThrow();
        assertThat(unchanged.getPurchaseRequestNumber()).isEqualTo("PR-2");
        assertThat(unchanged.getPurchaseRequestValidatedAt()).isNotNull();
        verifyNoInteractions(purchaseRequests);
    }

    @Test
    void unrelatedEditsWorkForValidatedAndLegacyInvoicesWithoutRemoteCalls() throws Exception {
        Invoice validated = invoices.save(invoice(
                "INV-VALIDATED",
                "Supplier",
                "PR-2",
                Instant.parse("2026-01-01T00:00:00Z")
        ));
        Invoice legacy = invoices.save(invoice("INV-LEGACY", "Supplier", "PR-2", null));

        mockMvc.perform(put("/invoice/{id}", validated.getId())
                        .with(finance())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplier": "Updated validated supplier"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(put("/invoice/{id}", legacy.getId())
                        .with(finance())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplier": "Updated legacy supplier"}
                                """))
                .andExpect(status().isOk());

        assertThat(invoices.findById(validated.getId()).orElseThrow().getSupplier())
                .isEqualTo("Updated validated supplier");
        assertThat(invoices.findById(legacy.getId()).orElseThrow().getSupplier())
                .isEqualTo("Updated legacy supplier");
        assertThat(invoices.findById(legacy.getId()).orElseThrow().getPurchaseRequestValidatedAt())
                .isNull();
        verifyNoInteractions(purchaseRequests);
    }

    @Test
    void exactFilterReturnsOnlyValidatedMatchesInDescendingOrder() throws Exception {
        Invoice firstMatch = invoice(
                "INV-1",
                "Supplier",
                "PR-2",
                Instant.parse("2026-01-01T00:00:00Z")
        );
        firstMatch.setAttachmentBytes(new byte[]{1, 2, 3});
        firstMatch = invoices.save(firstMatch);
        Invoice other = invoices.save(invoice(
                "INV-OTHER",
                "Supplier",
                "PR-3",
                Instant.parse("2026-01-01T00:00:00Z")
        ));
        Invoice legacyMatch = invoices.save(invoice("INV-LEGACY", "Supplier", "PR-2", null));
        Invoice secondMatch = invoices.save(invoice(
                "INV-2",
                "Supplier",
                "PR-2",
                Instant.parse("2026-01-02T00:00:00Z")
        ));

        mockMvc.perform(get("/invoice")
                        .with(finance())
                        .queryParam("purchase_request_number", "PR-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(secondMatch.getId()))
                .andExpect(jsonPath("$[1].id").value(firstMatch.getId()))
                .andExpect(jsonPath("$[0].attachment_bytes").doesNotExist());

        mockMvc.perform(get("/invoice").with(finance()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));

        assertThat(List.of(other.getId(), legacyMatch.getId()))
                .doesNotContain(firstMatch.getId(), secondMatch.getId());
        verifyNoInteractions(purchaseRequests);
    }

    @Test
    void existingReadsWorkWithoutPurchaseRequestService() throws Exception {
        invoices.save(invoice("INV-1", "Supplier", "PR-2", null));
        when(purchaseRequests.findByCode(anyString()))
                .thenThrow(PurchaseRequestException.unavailable());

        mockMvc.perform(get("/invoice").with(finance()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        verifyNoInteractions(purchaseRequests);
    }

    @Test
    void candidateProxyReturnsCandidatesAndRequiresFinanceAuthorization() throws Exception {
        when(purchaseRequests.findApproved()).thenReturn(List.of(reference("PR-2", "approved")));

        mockMvc.perform(get("/invoice/purchase-requests").with(finance()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].request_code").value("PR-2"))
                .andExpect(jsonPath("$[0].request_name").value("Annual renewal"))
                .andExpect(jsonPath("$[0].request_author").value("alice"))
                .andExpect(jsonPath("$[0].supplier_name").value("Atlassian"))
                .andExpect(jsonPath("$[0].request_approval_status").value("approved"));

        mockMvc.perform(get("/invoice/purchase-requests"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/invoice/purchase-requests").with(employee()))
                .andExpect(status().isForbidden());

        verify(purchaseRequests, times(1)).findApproved();
    }

    private org.springframework.test.web.servlet.ResultActions createInvoice(String requestCode)
            throws Exception {
        return mockMvc.perform(multipart("/invoice")
                .with(finance())
                .param("invoice_number", "INV-100")
                .param("supplier", "Atlassian")
                .param("purchase_request_number", requestCode)
                .param("invoice_sum", "125.50"));
    }

    private static Invoice invoice(
            String invoiceNumber,
            String supplier,
            String requestCode,
            Instant validatedAt
    ) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setSupplier(supplier);
        invoice.setPurchaseRequestNumber(requestCode);
        invoice.setPurchaseRequestValidatedAt(validatedAt);
        invoice.setInvoiceSum(new BigDecimal("100.00"));
        invoice.setInvoiceSumPaid(BigDecimal.ZERO);
        invoice.setInvoiceStatus("created");
        invoice.setUploadedBy("finadmin");
        return invoice;
    }

    private static PurchaseRequestReference reference(String code, String status) {
        return new PurchaseRequestReference(
                code,
                "Annual renewal",
                "alice",
                "Atlassian",
                status
        );
    }

    private static RequestPostProcessor finance() {
        return userWithRole("finance");
    }

    private static RequestPostProcessor employee() {
        return userWithRole("employee");
    }

    private static RequestPostProcessor userWithRole(String role) {
        User user = mock(User.class);
        when(user.getRole()).thenReturn(role);
        when(user.getUsername()).thenReturn("finadmin");
        return request -> {
            request.setAttribute(CurrentUserFilter.CURRENT_USER_ATTR, user);
            return request;
        };
    }
}
