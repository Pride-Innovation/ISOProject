package com.pridebank.isoproject.service;

import com.pridebank.isoproject.client.ESBClient;
import com.pridebank.isoproject.dto.*;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

    @Value("${esb.inter-switch-settlement-account}")
    private String interSwitchSettlementAccount; // Inter-switch settlement account

    @Value("${esb.tax-account}")
    private String taxAccount; // Transaction Tax (Excise Duty)

    @Value("${esb.pride-charge-account}")
    private String prideChargeCollectionAccount; // Pride Transaction charge

    @Value("${esb.inter-switch-charge-account}")
    private String interSwitchChargeCollectionAccount; // Inter switch Charge Account

    @Value("${esb.inter-switch-commissions-account}")
    private String interSwitchCommissionsAccount; // Inter switch commissions Account

    @Value("${esb.pride-commissions-settlement-account}")
    private String prideCommissionsSettlementAccount;

    // Charging scheme config
    @Value("${esb.charges.excise.rate:0}")
    private BigDecimal exciseDutyRate; // e.g., 0.15 for 15% of total charge

    @Value("${esb.charges.base.initial:2500}")
    private BigDecimal baseChargeInitial; // first band charge (≤ 500k)

    @Value("${esb.charges.base.band-size:500000}")
    private BigDecimal chargeBandSize; // 500k per band

    @Value("${esb.charges.base.increment:1000}")
    private BigDecimal chargeBandIncrement; // +1,000 per band beyond first

    @Value("${esb.charges.pride.share-percent:0.20}")
    private BigDecimal prideSharePercent; // Pride receives 20% of base charge

    @Value("${esb.charges.inter-switch.commission:0}")
    private BigDecimal interSwitchCommission; // Commission (not part of fee split)

    private final ObjectMapper objectMapper = new ObjectMapper();

    // transaction limit (major units)
    private static final BigDecimal TRANSACTION_LIMIT_MAJOR = new BigDecimal("5000000");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TRANSACTION_LIMIT_MINOR = TRANSACTION_LIMIT_MAJOR.multiply(HUNDRED);

    public String sendToEsb(String jsonRequest, IsoMessage isoMessage) {
        try {

            log.info("Iso Message ::: {}", isoMessage);
            log.info("Request Body ::: {}", jsonRequest);
            String authHeader = createBasicAuthHeader(atmUsername, atmPassword);
            AtmTransactionRequest request = objectMapper.readValue(jsonRequest, AtmTransactionRequest.class);
            String transactionType = request.getTransactionType();
            log.info("Request Transaction Type ::: {}", transactionType);
            log.info("Account Number ::: {}", request.getFromAccount());

            // ---- pre-check: transaction limit ----
            try {
                BigDecimal amountMinor = getBigDecimal(request);
                if (amountMinor != null && amountMinor.compareTo(TRANSACTION_LIMIT_MINOR) > 0) {
                    log.info("Transaction amount {} (minor) exceeds limit {} (minor) - short-circuiting ESB call",
                            amountMinor, TRANSACTION_LIMIT_MINOR);
                    AtmTransactionResponse atmResp = AtmTransactionResponse.builder()
                            .responseCode("EXCEEDS_LIMIT")
                            .message("Transaction amount exceeds allowed limit")
                            .build();
                    atmResp.setResponseCode(normalizeResponseCode(atmResp.getResponseCode()));
                    return objectMapper.writeValueAsString(atmResp);
                }
            } catch (Exception e) {
                log.warn("Failed to evaluate transaction limit, continuing: {}", e.getMessage());
            }

            String externalReference = generateExternalReference();
            request.setExternalRef(externalReference);

            // Build charges (Excise Duty + Pride + InterSwitch) for financial transactions
            request.setCharges(buildTransactionCharges(transactionType, request));

            /*
                Add Commissions for deposits requests
             */
            if (Objects.equals(transactionType, "DEPOSIT")) {
                request.setCommission(processTransactionCommission(externalReference));
            }

            /*
                Add Date ranges for mini statement
             */
            if (Objects.equals(transactionType, "MINI_STATEMENT")) {
                MinistatementDates dates = generateStartEndDatesForMinistatement();
                request.setFromDate(dates.getFromDate());
                request.setToDate(dates.getToDate());
            }

            if (Objects.equals(transactionType, "MINI_STATEMENT") ||
                    Objects.equals(transactionType, "BALANCE_INQUIRY")) {
                if (request.getFromAccount() != null) {
                    request.setAccountNumber(request.getFromAccount());
                } else {
                    request.setAccountNumber(request.getToAccount());
                }
            }

            if (Objects.equals(transactionType, "DEPOSIT") ||
                    Objects.equals(transactionType, "WITHDRAWAL") ||
                    Objects.equals(transactionType, "PURCHASE")) {
                SourceDestinationAccounts data = determineSourceAndDestinationAccounts(
                        transactionType,
                        request.getFromAccount(),
                        request.getToAccount()
                );
                request.setFromAccount(data.getFromAccount());
                request.setTargetAccount(data.getTargetAccount());
            }

            ResponseEntity<?> response = callESBEndPointBasedOnTransactionType(transactionType, authHeader, request);

            Object body = response != null ? response.getBody() : null;
            if (response == null) {
                return createErrorResponse("No response from ESB");
            }

            AtmTransactionResponse atmResp;
            if (body != null) {
                atmResp = objectMapper.convertValue(body, AtmTransactionResponse.class);
            } else {
                int statusValue = response.getStatusCode().value();
                String reason;
                var resolved = org.springframework.http.HttpStatus.resolve(statusValue);
                if (resolved != null) {
                    reason = resolved.getReasonPhrase();
                } else {
                    reason = response.getStatusCode().toString();
                }

                log.info("Response Status Code ::: {}", response.getStatusCode());

                atmResp = AtmTransactionResponse.builder()
                        .responseCode(response.getStatusCode().is2xxSuccessful() ? "00" :
                                response.getStatusCode().is3xxRedirection() ? "51" :
                                        response.getStatusCode().is4xxClientError() ? "14" : "96")
                        .message(reason)
                        .build();
            }

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
                        transactionType.equals("PURCHASE") ? esbClient.PurchaseRequestPostRequest(authHeader, request) :
                                transactionType.equals("BALANCE_INQUIRY") ? esbClient.BalanceInquiryRequestPostRequest(authHeader, request) :
                                        transactionType.equals("MINI_STATEMENT") ? esbClient.MiniStatementRequestPostRequest(authHeader, request) : null;
    }

    /**
     * Map common ESB textual codes to ISO39 numeric codes (2-char). If already numeric return as-is.
     */
    private String normalizeResponseCode(String code) {
        if (code == null) return "96";
        String c = code.trim();
        if (c.matches("\\d{2}")) return c;
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

    /*
     * Charges: Excise Duty (on total charge), Pride (20% of base), InterSwitch (80% of base)
     * Applies only to DEPOSIT/WITHDRAWAL/PURCHASE; returns empty list for inquiries.
     */
    private List<Charge> buildTransactionCharges(String transactionType, AtmTransactionRequest request) {
        if (!Arrays.asList("DEPOSIT", "WITHDRAWAL", "PURCHASE").contains(transactionType)) {
            return Collections.emptyList();
        }

        BigDecimal baseAmount = amountFromRequest(request); // major units
        BigDecimal baseCharge = calculateBaseCharge(baseAmount); // whole UGX

        // Split base charge
        BigDecimal prideFee = baseCharge.multiply(prideSharePercent).setScale(0, RoundingMode.HALF_UP);
        BigDecimal interSwitchFee = baseCharge.subtract(prideFee).setScale(0, RoundingMode.HALF_UP);

        // Excise duty on TOTAL CHARGE (baseCharge), not on amount
        BigDecimal exciseAmount = baseCharge.multiply(exciseDutyRate).setScale(0, RoundingMode.HALF_UP);

        List<Charge> charges = new ArrayList<>();

        if (prideFee.compareTo(BigDecimal.ZERO) > 0) {
            charges.add(Charge.builder()
                    .amount(prideFee)
                    .description("PRIDE CHARGE")
                    .toAccount(prideChargeCollectionAccount)
                    .build());
        }

        if (interSwitchFee.compareTo(BigDecimal.ZERO) > 0) {
            charges.add(Charge.builder()
                    .amount(interSwitchFee)
                    .description("INTER SWITCH CHARGE")
                    .toAccount(interSwitchChargeCollectionAccount)
                    .build());
        }

        if (exciseAmount.compareTo(BigDecimal.ZERO) > 0) {
            charges.add(Charge.builder()
                    .amount(exciseAmount)
                    .description("EXCISE DUTY")
                    .toAccount(taxAccount)
                    .build());
        }

        return charges;
    }

    private BigDecimal calculateBaseCharge(BigDecimal amountMajor) {
        if (amountMajor == null) return baseChargeInitial;

        // ≤ first band: initial charge
        if (amountMajor.compareTo(chargeBandSize) <= 0) {
            return baseChargeInitial;
        }

        // Bands beyond first: ceil((amount - bandSize) / bandSize)
        BigDecimal over = amountMajor.subtract(chargeBandSize);
        BigDecimal bandsBeyondFirst = over.divide(chargeBandSize, 0, RoundingMode.CEILING);
        return baseChargeInitial.add(chargeBandIncrement.multiply(bandsBeyondFirst));
    }

    private BigDecimal amountFromRequest(AtmTransactionRequest request) {
        if (request.getAmount() != null) return request.getAmount();
        if (request.getAmountMinor() != null && !request.getAmountMinor().isBlank()) {
            String digits = request.getAmountMinor().replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) return new BigDecimal(digits).movePointLeft(2);
        }
        return BigDecimal.ZERO;
    }

    private Commission processTransactionCommission(String externalReference) {
        return Commission.builder()
                .fromAccount(prideCommissionsSettlementAccount)
                .toAccount(interSwitchCommissionsAccount)
                .amount(interSwitchCommission)
                .description(String.format("Commission for %s", externalReference))
                .build();
    }

    private MinistatementDates generateStartEndDatesForMinistatement() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusMonths(3);
        return MinistatementDates
                .builder()
                .fromDate(fromDate.format(formatter))
                .toDate(toDate.format(formatter))
                .build();
    }

    private SourceDestinationAccounts determineSourceAndDestinationAccounts(
            String transaction,
            String fromAccount,
            String toAccount
    ) {
        boolean b = Objects.equals(transaction, "WITHDRAWAL")
                || Objects.equals(transaction, "PURCHASE");

        return SourceDestinationAccounts
                .builder()
                .fromAccount(
                        Objects.equals(transaction, "DEPOSIT") ? interSwitchSettlementAccount :
                                b ? fromAccount : null)
                .targetAccount(
                        Objects.equals(transaction, "DEPOSIT") ? toAccount :
                                b ? interSwitchSettlementAccount : null)
                .build();
    }
}