package com.casestudy.invoiceapp.purchaserequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PurchaseRequestClientTests {

    @Test
    void approvedCandidatesSendTokenAndDecodeMinimalContract() {
        TestBoundary boundary = boundary();
        boundary.server.expect(once(), requestTo(
                        "http://pr.test/integration/purchase-requests?status=approved"
                ))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(PurchaseRequestClient.INTEGRATION_HEADER, "secret"))
                .andRespond(withSuccess("""
                        [
                          {
                            "request_code": "PR-2",
                            "request_name": "Annual renewal",
                            "request_author": "alice",
                            "supplier_name": "Atlassian",
                            "request_approval_status": "approved"
                          }
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<PurchaseRequestReference> result = boundary.client.findApproved();

        assertThat(result).containsExactly(new PurchaseRequestReference(
                "PR-2",
                "Annual renewal",
                "alice",
                "Atlassian",
                "approved"
        ));
        boundary.server.verify();
    }

    @Test
    void exactLookupReturnsApprovedAndNonApprovedRecords() {
        TestBoundary boundary = boundary();
        boundary.server.expect(once(), requestTo(
                        "http://pr.test/integration/purchase-requests/PR-1"
                ))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(PurchaseRequestClient.INTEGRATION_HEADER, "secret"))
                .andRespond(withSuccess("""
                        {
                          "request_code": "PR-1",
                          "request_name": "Pending request",
                          "request_author": "alice",
                          "supplier_name": "Supplier",
                          "request_approval_status": "sent for approval"
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<PurchaseRequestReference> result = boundary.client.findByCode("PR-1");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().requestApprovalStatus()).isEqualTo("sent for approval");
        boundary.server.verify();
    }

    @Test
    void exactLookupMapsNotFoundToEmpty() {
        TestBoundary boundary = boundary();
        boundary.server.expect(once(), requestTo(
                        "http://pr.test/integration/purchase-requests/PR-404"
                ))
                .andRespond(withResourceNotFound());

        assertThat(boundary.client.findByCode("PR-404")).isEmpty();
        boundary.server.verify();
    }

    @Test
    void timeoutMapsToServiceUnavailable() {
        PurchaseRequestClient client = clientWithFailure(new SocketTimeoutException("timed out"));

        assertError(
                () -> client.findByCode("PR-2"),
                503,
                "PURCHASE_REQUEST_SERVICE_UNAVAILABLE"
        );
    }

    @Test
    void connectionFailureMapsToServiceUnavailable() {
        PurchaseRequestClient client = clientWithFailure(new ConnectException("connection refused"));

        assertError(
                client::findApproved,
                503,
                "PURCHASE_REQUEST_SERVICE_UNAVAILABLE"
        );
    }

    @Test
    void upstreamServerErrorMapsToServiceUnavailable() {
        TestBoundary boundary = boundary();
        boundary.server.expect(once(), requestTo(
                        "http://pr.test/integration/purchase-requests/PR-2"
                ))
                .andRespond(withServerError());

        assertError(
                () -> boundary.client.findByCode("PR-2"),
                503,
                "PURCHASE_REQUEST_SERVICE_UNAVAILABLE"
        );
        boundary.server.verify();
    }

    @Test
    void upstreamAuthenticationFailureMapsToServiceError() {
        TestBoundary boundary = boundary();
        boundary.server.expect(once(), requestTo(
                        "http://pr.test/integration/purchase-requests/PR-2"
                ))
                .andRespond(withUnauthorizedRequest());

        assertError(
                () -> boundary.client.findByCode("PR-2"),
                502,
                "PURCHASE_REQUEST_SERVICE_ERROR"
        );
        boundary.server.verify();
    }

    @Test
    void malformedJsonMapsToServiceError() {
        TestBoundary boundary = boundary();
        boundary.server.expect(once(), requestTo(
                        "http://pr.test/integration/purchase-requests/PR-2"
                ))
                .andRespond(withSuccess("{not-json", MediaType.APPLICATION_JSON));

        assertError(
                () -> boundary.client.findByCode("PR-2"),
                502,
                "PURCHASE_REQUEST_SERVICE_ERROR"
        );
        boundary.server.verify();
    }

    @Test
    void missingFieldsAndIneligibleCandidatePayloadsMapToServiceError() {
        TestBoundary malformed = boundary();
        malformed.server.expect(once(), requestTo(
                        "http://pr.test/integration/purchase-requests/PR-2"
                ))
                .andRespond(withSuccess("""
                        {
                          "request_code": "PR-2",
                          "request_name": "Annual renewal",
                          "request_author": "alice",
                          "supplier_name": "Atlassian"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertError(
                () -> malformed.client.findByCode("PR-2"),
                502,
                "PURCHASE_REQUEST_SERVICE_ERROR"
        );
        malformed.server.verify();

        TestBoundary ineligibleCandidate = boundary();
        ineligibleCandidate.server.expect(once(), requestTo(
                        "http://pr.test/integration/purchase-requests?status=approved"
                ))
                .andRespond(withSuccess("""
                        [
                          {
                            "request_code": "PR-1",
                            "request_name": "Pending request",
                            "request_author": "alice",
                            "supplier_name": "Supplier",
                            "request_approval_status": "initiated"
                          }
                        ]
                        """, MediaType.APPLICATION_JSON));

        assertError(
                ineligibleCandidate.client::findApproved,
                502,
                "PURCHASE_REQUEST_SERVICE_ERROR"
        );
        ineligibleCandidate.server.verify();
    }

    @Test
    void blankLocalTokenFailsClosedWithoutCallingUpstream() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://pr.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PurchaseRequestClient client = new PurchaseRequestClient(builder.build(), " ");

        assertError(
                client::findApproved,
                502,
                "PURCHASE_REQUEST_SERVICE_ERROR"
        );
        server.verify();
    }

    private static TestBoundary boundary() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://pr.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new TestBoundary(new PurchaseRequestClient(builder.build(), "secret"), server);
    }

    private static PurchaseRequestClient clientWithFailure(java.io.IOException failure) {
        ClientHttpRequestFactory requestFactory = (uri, httpMethod) -> {
            throw failure;
        };
        RestClient restClient = RestClient.builder()
                .baseUrl("http://pr.test")
                .requestFactory(requestFactory)
                .build();
        return new PurchaseRequestClient(restClient, "secret");
    }

    private static void assertError(Runnable call, int status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(PurchaseRequestException.class, exception -> {
                    assertThat(exception.getStatus().value()).isEqualTo(status);
                    assertThat(exception.getCode()).isEqualTo(code);
                });
    }

    private record TestBoundary(
            PurchaseRequestClient client,
            MockRestServiceServer server
    ) {
    }
}
