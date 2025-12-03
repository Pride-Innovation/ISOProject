package com.pridebank.isoproject.client;

import com.pridebank.isoproject.exception.AuthenticationFailedException;
import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
public class ESBErrorDecoder implements ErrorDecoder {

    private final ErrorDecoder defaultErrorDecoder = new Default();

    @Override
    public Exception decode(String methodKey, Response response) {
        String errorMessage = extractErrorMessage(response);

        log.error("ESB Error - Method: {}, Status: {}, Message: {}",
                methodKey, response.status(), errorMessage);

        return switch (response.status()) {
            case 400 -> {
                if (methodKey.contains("validateAccount")) {
                    yield new InvalidAccountException(errorMessage);
                }
                yield new AuthenticationFailedException(
                        "Bad request to ESB: " + errorMessage
                );
                // Don't throw exception for 400, let it pass through for account validation
            }
            case 401 -> new AuthenticationFailedException("Invalid credentials");
            case 403 -> new AuthenticationFailedException("Access forbidden");
            case 404 -> new AuthenticationFailedException("ESB endpoint not found");
            case 500 -> new AuthenticationFailedException(
                    "ESB internal server error: " + errorMessage
            );
            case 503 -> new AuthenticationFailedException("ESB service unavailable");
            case 504 -> new AuthenticationFailedException("ESB gateway timeout");
            default -> defaultErrorDecoder.decode(methodKey, response);
        };
    }

    private String extractErrorMessage(Response response) {
        try {
            if (response.body() != null) {
                byte[] bodyData = response.body().asInputStream().readAllBytes();
                return new String(bodyData, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to read error response body", e);
        }
        return "Unknown error";
    }

    /**
     * Custom exception for invalid account (400 status)
     */
    public static class InvalidAccountException extends RuntimeException {
        public InvalidAccountException(String message) {
            super(message);
        }
    }
}
