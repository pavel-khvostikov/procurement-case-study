package com.casestudy.invoiceapp.purchaserequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class PurchaseRequestClient {

    static final String INTEGRATION_HEADER = "X-Integration-Token";

    private static final Set<String> VALID_STATUSES =
            Set.of("initiated", "sent for approval", "approved", "rejected");

    private final RestClient restClient;
    private final String integrationToken;

    @Autowired
    public PurchaseRequestClient(
            RestClient.Builder builder,
            @Value("${purchase-request.api-base-url}") String baseUrl,
            @Value("${purchase-request.integration-token}") String integrationToken,
            @Value("${purchase-request.connect-timeout}") Duration connectTimeout,
            @Value("${purchase-request.response-timeout}") Duration responseTimeout
    ) {
        this(buildClient(builder, baseUrl, connectTimeout, responseTimeout), integrationToken);
    }

    PurchaseRequestClient(RestClient restClient, String integrationToken) {
        this.restClient = restClient;
        this.integrationToken = integrationToken;
    }

    public List<PurchaseRequestReference> findApproved() {
        requireConfiguredToken();
        try {
            List<PurchaseRequestReference> references = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/integration/purchase-requests")
                            .queryParam("status", "approved")
                            .build())
                    .header(INTEGRATION_HEADER, integrationToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw PurchaseRequestException.unavailable();
                    })
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw PurchaseRequestException.serviceError();
                    })
                    .body(new ParameterizedTypeReference<>() {
                    });

            if (references == null) {
                throw PurchaseRequestException.serviceError();
            }
            references.forEach(reference -> validateReference(reference, true));
            return List.copyOf(references);
        } catch (PurchaseRequestException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw PurchaseRequestException.unavailable();
        } catch (RestClientException exception) {
            throw PurchaseRequestException.serviceError();
        }
    }

    public Optional<PurchaseRequestReference> findByCode(String requestCode) {
        requireConfiguredToken();
        try {
            PurchaseRequestReference reference = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .pathSegment("integration", "purchase-requests", requestCode)
                            .build())
                    .header(INTEGRATION_HEADER, integrationToken)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, response) -> {
                        throw new NotFoundSignal();
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw PurchaseRequestException.unavailable();
                    })
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw PurchaseRequestException.serviceError();
                    })
                    .body(PurchaseRequestReference.class);

            validateReference(reference, false);
            return Optional.of(reference);
        } catch (NotFoundSignal exception) {
            return Optional.empty();
        } catch (PurchaseRequestException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw PurchaseRequestException.unavailable();
        } catch (RestClientException exception) {
            throw PurchaseRequestException.serviceError();
        }
    }

    private void requireConfiguredToken() {
        if (integrationToken == null || integrationToken.isBlank()) {
            throw PurchaseRequestException.serviceError();
        }
    }

    private static void validateReference(PurchaseRequestReference reference, boolean mustBeApproved) {
        if (reference == null
                || isBlank(reference.requestCode())
                || isBlank(reference.requestName())
                || isBlank(reference.requestAuthor())
                || isBlank(reference.supplierName())
                || isBlank(reference.requestApprovalStatus())
                || !VALID_STATUSES.contains(reference.requestApprovalStatus())
                || (mustBeApproved && !"approved".equals(reference.requestApprovalStatus()))) {
            throw PurchaseRequestException.serviceError();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static RestClient buildClient(
            RestClient.Builder builder,
            String baseUrl,
            Duration connectTimeout,
            Duration responseTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(responseTimeout);
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    private static final class NotFoundSignal extends RuntimeException {
    }
}
