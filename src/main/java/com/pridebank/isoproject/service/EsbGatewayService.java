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

            if (!response.getStatusCode().is2xxSuccessful()) {
                return createErrorResponse("ESB communication failed");
            }

            return objectMapper.writeValueAsString(response.getBody());

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

}