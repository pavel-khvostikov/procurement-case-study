package com.casestudy.invoiceapp.integration;

import com.casestudy.invoiceapp.purchaserequest.PurchaseRequestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:invoice-integration-unconfigured;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "invoice.integration-token="
})
class InvoiceIntegrationUnconfiguredTokenTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PurchaseRequestClient purchaseRequests;

    @Test
    void rejectsRequestsWhenTheIntegrationTokenIsNotConfigured() throws Exception {
        mockMvc.perform(get("/integration/invoices")
                        .header(
                                InvoiceIntegrationTokenAuthenticator.HEADER_NAME,
                                "attempted-token"
                        )
                        .queryParam("purchase_request_number", "PR-2"))
                .andExpect(status().isUnauthorized());
    }
}
