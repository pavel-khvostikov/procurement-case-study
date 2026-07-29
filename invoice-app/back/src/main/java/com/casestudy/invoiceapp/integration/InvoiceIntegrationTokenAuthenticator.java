package com.casestudy.invoiceapp.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InvoiceIntegrationTokenAuthenticator {

    public static final String HEADER_NAME = "X-Integration-Token";

    private final byte[] configuredToken;
    private final boolean configured;

    public InvoiceIntegrationTokenAuthenticator(
            @Value("${invoice.integration-token:}") String configuredToken
    ) {
        String token = configuredToken == null ? "" : configuredToken;
        this.configuredToken = token.getBytes(StandardCharsets.UTF_8);
        this.configured = !token.isBlank();
    }

    public void requireValid(String suppliedToken) {
        byte[] supplied = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        boolean matches = MessageDigest.isEqual(configuredToken, supplied);

        if (!configured || suppliedToken == null || suppliedToken.isBlank() || !matches) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid integration token"
            );
        }
    }
}
