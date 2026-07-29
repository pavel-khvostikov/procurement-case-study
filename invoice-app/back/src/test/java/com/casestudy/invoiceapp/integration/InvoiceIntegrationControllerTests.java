package com.casestudy.invoiceapp.integration;

import com.casestudy.invoiceapp.auth.CurrentUserFilter;
import com.casestudy.invoiceapp.invoice.Invoice;
import com.casestudy.invoiceapp.invoice.InvoiceRepository;
import com.casestudy.invoiceapp.purchaserequest.PurchaseRequestClient;
import com.casestudy.invoiceapp.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:invoice-integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "invoice.integration-token=test-invoice-token"
})
class InvoiceIntegrationControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    void rejectsMissingBlankAndInvalidTokens() throws Exception {
        mockMvc.perform(get("/integration/invoices")
                        .queryParam("purchase_request_number", "PR-2"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/integration/invoices")
                        .header(InvoiceIntegrationTokenAuthenticator.HEADER_NAME, "")
                        .queryParam("purchase_request_number", "PR-2"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/integration/invoices")
                        .header(InvoiceIntegrationTokenAuthenticator.HEADER_NAME, "wrong-token")
                        .queryParam("purchase_request_number", "PR-2"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/integration/invoices")
                        .with(finance())
                        .queryParam("purchase_request_number", "PR-2"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(purchaseRequests);
    }

    @Test
    void requiresNonBlankPurchaseRequestNumber() throws Exception {
        mockMvc.perform(get("/integration/invoices")
                        .header(
                                InvoiceIntegrationTokenAuthenticator.HEADER_NAME,
                                "test-invoice-token"
                        ))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/integration/invoices")
                        .header(
                                InvoiceIntegrationTokenAuthenticator.HEADER_NAME,
                                "test-invoice-token"
                        )
                        .queryParam("purchase_request_number", "   "))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(purchaseRequests);
    }

    @Test
    void returnsOnlyValidatedExactMatchesAsMinimalDtosInDescendingOrder()
            throws Exception {
        Invoice firstMatch = invoice(
                "INV-1",
                "PR-2",
                "created",
                Instant.parse("2026-01-01T00:00:00Z")
        );
        firstMatch.setAttachmentBytes(new byte[]{1, 2, 3});
        firstMatch.setAttachmentFilename("private.pdf");
        firstMatch = invoices.save(firstMatch);

        invoices.save(invoice(
                "INV-OTHER",
                "PR-3",
                "created",
                Instant.parse("2026-01-01T00:00:00Z")
        ));
        invoices.save(invoice("INV-LEGACY", "PR-2", "paid", null));
        Invoice secondMatch = invoices.save(invoice(
                "INV-2",
                "PR-2",
                "paid",
                Instant.parse("2026-01-02T00:00:00Z")
        ));

        String responseBody = mockMvc.perform(get("/integration/invoices")
                        .header(
                                InvoiceIntegrationTokenAuthenticator.HEADER_NAME,
                                "test-invoice-token"
                        )
                        .queryParam("purchase_request_number", "PR-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(secondMatch.getId()))
                .andExpect(jsonPath("$[0].invoice_number").value("INV-2"))
                .andExpect(jsonPath("$[0].invoice_status").value("paid"))
                .andExpect(jsonPath("$[1].id").value(firstMatch.getId()))
                .andExpect(jsonPath("$[1].invoice_number").value("INV-1"))
                .andExpect(jsonPath("$[1].invoice_status").value("created"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode response = objectMapper.readTree(responseBody);
        assertThat(response.get(0).size()).isEqualTo(3);
        assertThat(fieldNames(response.get(0)))
                .containsExactlyInAnyOrder("id", "invoice_number", "invoice_status");
        assertThat(response.get(1).size()).isEqualTo(3);
        assertThat(fieldNames(response.get(1)))
                .containsExactlyInAnyOrder("id", "invoice_number", "invoice_status");
        verifyNoInteractions(purchaseRequests);
    }

    @Test
    void returnsAnEmptyListWhenNoValidatedInvoicesMatch() throws Exception {
        invoices.save(invoice("INV-LEGACY", "PR-2", "created", null));

        mockMvc.perform(get("/integration/invoices")
                        .header(
                                InvoiceIntegrationTokenAuthenticator.HEADER_NAME,
                                "test-invoice-token"
                        )
                        .queryParam("purchase_request_number", "PR-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verifyNoInteractions(purchaseRequests);
    }

    private static Invoice invoice(
            String invoiceNumber,
            String purchaseRequestNumber,
            String invoiceStatus,
            Instant validatedAt
    ) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setSupplier("Private supplier");
        invoice.setPurchaseRequestNumber(purchaseRequestNumber);
        invoice.setPurchaseRequestValidatedAt(validatedAt);
        invoice.setInvoiceSum(new BigDecimal("100.00"));
        invoice.setInvoiceSumPaid(
                "paid".equals(invoiceStatus)
                        ? new BigDecimal("100.00")
                        : BigDecimal.ZERO
        );
        invoice.setInvoiceStatus(invoiceStatus);
        invoice.setUploadedBy("finadmin");
        return invoice;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static RequestPostProcessor finance() {
        User user = mock(User.class);
        when(user.getRole()).thenReturn("finance");
        when(user.getUsername()).thenReturn("finadmin");
        return request -> {
            request.setAttribute(CurrentUserFilter.CURRENT_USER_ATTR, user);
            return request;
        };
    }
}
