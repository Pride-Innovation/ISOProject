package com.pridebank.isoproject.service;

import com.pridebank.isoproject.client.ESBClient;
import com.pridebank.isoproject.dto.AtmTransactionRequest;
import com.pridebank.isoproject.dto.AtmTransactionResponse;
import com.pridebank.isoproject.dto.Charge;
import com.pridebank.isoproject.dto.Commission;
import com.solab.iso8583.IsoMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class EsbGatewayService {

    private final ESBClient esbClient;
    private static final Random random = new Random();

    @Value("${esb.username}")
    private String atmUsername;

    @Value("${esb.password}")
    private String atmPassword;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // transaction limit (major units)
    private static final BigDecimal TRANSACTION_LIMIT_MAJOR = new BigDecimal("5000000");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TRANSACTION_LIMIT_MINOR = TRANSACTION_LIMIT_MAJOR.multiply(HUNDRED);

    public String sendToEsb(String jsonRequest, IsoMessage isoMessage) {
        try {

            log.info("Iso Message ::: {}", isoMessage);
            String authHeader = createBasicAuthHeader(atmUsername, atmPassword);
            AtmTransactionRequest request = objectMapper.readValue(jsonRequest, AtmTransactionRequest.class);
            String transactionType = request.getTransactionType();
            log.info("Request Transaction Type ::: {}", transactionType);
            log.info("Account Number ::: {}", request.getFromAccount());

            // ---- pre-check: transaction limit ----
            try {
                // determine amount in minor units
                BigDecimal amountMinor = getBigDecimal(request);

                if (amountMinor != null && amountMinor.compareTo(TRANSACTION_LIMIT_MINOR) > 0) {
                    log.info("Transaction amount {} (minor) exceeds limit {} (minor) - short-circuiting ESB call",
                            amountMinor, TRANSACTION_LIMIT_MINOR);
                    AtmTransactionResponse atmResp = AtmTransactionResponse.builder()
                            .responseCode("EXCEEDS_LIMIT")
                            .message("Transaction amount exceeds allowed limit")
                            .build();
                    // normalize to expected JSON and return immediately
                    atmResp.setResponseCode(normalizeResponseCode(atmResp.getResponseCode()));
                    return objectMapper.writeValueAsString(atmResp);
                }
            } catch (Exception e) {
                log.warn("Failed to evaluate transaction limit, continuing: {}", e.getMessage());
            }

            String externalReference = generateExternalReference();
            request.setExternalRef(externalReference);

            request.setCharges(Collections.singletonList(processTransactionCharge()));

            /*
                Add Commissions for deposits requests
             */

            if (Objects.equals(transactionType, "DEPOSIT")) {
                request.setCommission(processTransactionCommission(
                        request.getToAccount(),
                        externalReference
                ));
            }

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
                log.info("Response Status Code ::: {}", response.getStatusCode());

                atmResp = AtmTransactionResponse.builder()
                        .responseCode(response.getStatusCode().is2xxSuccessful() ? "00" :
                                response.getStatusCode().is3xxRedirection() ? "51" : // Handle Insufficient Account Balance
                                        response.getStatusCode().is4xxClientError() ? "14" : // Handle Invalid Account Number
                                                "96")
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

    private static BigDecimal getBigDecimal(AtmTransactionRequest request) {
        log.info("Request Amount ::: {}", request.getAmount());
        BigDecimal amountMinor = null;
        if (request.getAmount() != null) {
            amountMinor = request.getAmount().multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP);
        } else if (request.getAmountMinor() != null && !request.getAmountMinor().isBlank()) {
            String digits = request.getAmountMinor().replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) amountMinor = new BigDecimal(digits);
        }
        return amountMinor;
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
            default -> "96";
        };
    }

    private String formatMinorForService(BigDecimal value) {
        if (value == null) return "0";
        BigDecimal minor = value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP);
        return minor.toPlainString().replaceAll("\\D", "");
    }

    private String generateExternalReference() {
        long timestamp = System.currentTimeMillis();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        String formattedTimestamp = sdf.format(new Date(timestamp));

        String randomLetters = generateRandomLetters();

        String randomDigits = String.format("%05d", random.nextInt(100000));
        return "Ref " + formattedTimestamp + randomLetters + randomDigits;
    }


    private String generateRandomLetters() {
        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            char randomChar = (char) ('A' + random.nextInt(26)); // Random char between A-Z
            sb.append(randomChar);
        }
        return sb.toString();
    }

    private Charge processTransactionCharge() {
        return Charge.builder()
                .amount(BigDecimal.valueOf(1000))
                .description("VAT")
                .toAccount("212206047427801")
                .build();
    }


    private Commission processTransactionCommission(String toAccount, String externalReference) {
        return Commission.builder()
                .fromAccount("212206047427801")
                .toAccount(toAccount)
                .amount(BigDecimal.valueOf(1000))
                .description(String.format("Commission for %s", externalReference))
                .build();
    }
}