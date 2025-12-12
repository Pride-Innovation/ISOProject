package com.pridebank.isoproject.service;

import com.pridebank.isoproject.client.ESBClient;
import com.pridebank.isoproject.dto.AtmTransactionRequest;
import com.pridebank.isoproject.dto.AtmTransactionResponse;
import com.solab.iso8583.IsoMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
@Slf4j
@RequiredArgsConstructor
public class EsbGatewayService {

    private final ESBClient esbClient;

    @Value("${esb.username}")
    private String atmUsername;

    @Value("${esb.password}")
    private String atmPassword;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String sendToEsb(String jsonRequest, IsoMessage isoMessage) {
        try {

            log.info("Iso Message ::: {}", isoMessage);
            String authHeader = createBasicAuthHeader(atmUsername, atmPassword);
            AtmTransactionRequest request = objectMapper.readValue(jsonRequest, AtmTransactionRequest.class);
            log.info("Request Object ::: {}", request);
            String transactionType = request.getTransactionType();

            System.out.println("Request Type ::: " + transactionType);

            ResponseEntity<?> response = callESBEndPointBasedOnTransactionType(transactionType, authHeader, request);

            // Normalize responses here: accept 2xx or structured error body
            Object body = response != null ? response.getBody() : null;

            if (response == null) {
                return createErrorResponse("No response from ESB");
            }

            // If non-2xx but body exists try to map it; otherwise return standardized system error
            AtmTransactionResponse atmResp;
            if (body != null) {
                atmResp = objectMapper.convertValue(body, AtmTransactionResponse.class);
            } else {
                // Spring 6: ResponseEntity.getStatusCode() returns HttpStatusCode (no getReasonPhrase()).
                // Resolve numeric status to HttpStatus to obtain reason phrase, fallback to toString().
                response.getStatusCode();
                int statusValue = response.getStatusCode().value();
                String reason;
                var resolved = org.springframework.http.HttpStatus.resolve(statusValue);
                if (resolved != null) {
                    reason = resolved.getReasonPhrase();
                } else {
                    response.getStatusCode();
                    reason = response.getStatusCode().toString();
                }

                response.getStatusCode();
                atmResp = AtmTransactionResponse.builder()
                        .responseCode(response.getStatusCode().is2xxSuccessful() ? "00" : "96")
                        .message(reason)
                        .build();
            }

            // Ensure ISO-compatible responseCode and other normalization
            atmResp.setResponseCode(normalizeResponseCode(atmResp.getResponseCode()));
            if (atmResp.getAmountMinor() == null && atmResp.getAmount() != null) {
                atmResp.setAmountMinor(formatMinorForService(atmResp.getAmount()));
            }

            return objectMapper.writeValueAsString(atmResp);

        } catch (Exception e) {
            log.error("ESB communication failed", e);
            return createErrorResponse(e.getMessage());
        }
    }

    private String createBasicAuthHeader(String username, String password) {
        String auth = username + ":" + password;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
        return "Basic " + new String(encodedAuth);
    }

    private String createErrorResponse(String message) {
        try {
            AtmTransactionResponse errorResponse = AtmTransactionResponse.builder()
                    .responseCode("SYSTEM_ERROR")
                    .message(message)
                    .build();
            return objectMapper.writeValueAsString(errorResponse);
        } catch (Exception e) {
            return "{\"responseCode\":\"SYSTEM_ERROR\",\"message\":\"Unknown error\"}";
        }
    }


    private ResponseEntity<?> callESBEndPointBasedOnTransactionType(
            String transactionType,
            String authHeader,
            AtmTransactionRequest request
    ) {
        return transactionType.equals("WITHDRAWAL") ? esbClient.WithdrawalRequestPostRequest(authHeader, request) :
                transactionType.equals("DEPOSIT") ? esbClient.DepositRequestPostRequest(authHeader, request) :
                        transactionType.equals("TRANSFER") ? esbClient.TransferRequestPostRequest(authHeader, request) :
                                transactionType.equals("BALANCE_INQUIRY") ? esbClient.BalanceInquiryRequestPostRequest(authHeader, request) :
                                        esbClient.MiniStatementRequestPostRequest(authHeader, request);
    }

    /**
     * Map common ESB textual codes to ISO39 numeric codes (2-char). If already numeric return as-is.
     */
    private String normalizeResponseCode(String code) {
        if (code == null) return "96";
        String c = code.trim();
        if (c.matches("\\d{2}")) return c;
        // common mappings
        String up = c.toUpperCase();
        return switch (up) {
            case "OK", "SUCCESS", "APPROVED", "APPROVAL" -> "00";
            case "INSUFFICIENT_FUNDS", "INSUFFICIENT FUNDS", "NOT_ENOUGH_FUNDS" -> "51";
            case "INVALID_ACCOUNT", "ACCOUNT_NOT_FOUND", "NO_ACCOUNT" -> "14";
            case "EXCEEDS_LIMIT", "LIMIT_EXCEEDED" -> "61";
            case "AUTH_FAILED", "DECLINED" -> "05";
            case "DUPLICATE" -> "94";
//            case "TIMEOUT", "UNAVAILABLE", "SERVICE_UNAVAILABLE" -> "96";
            default -> "96";
        };
    }

    private String formatMinorForService(BigDecimal value) {
        if (value == null) return "0";
        BigDecimal minor = value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP);
        return minor.toPlainString().replaceAll("\\D", "");
    }

}