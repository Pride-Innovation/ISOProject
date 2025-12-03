package com.pridebank.isoproject.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AtmTransactionRequest {

    @JsonProperty("messageType")
    private String messageType;

    @JsonProperty("transactionType")
    private String transactionType;

    @JsonProperty("cardNumber")
    private String cardNumber;

    @JsonProperty("accountNumber")
    private String accountNumber;

    @JsonProperty("amount")
    private BigDecimal amount;

    @JsonProperty("currencyCode")
    private String currencyCode;

    @JsonProperty("stan")
    private String stan;

    @JsonProperty("terminalId")
    private String terminalId;

    @JsonProperty("processingCode")
    private String processingCode;

    @JsonProperty("fromAccount")
    private String fromAccount;

    @JsonProperty("toAccount")
    private String toAccount;

    @JsonProperty("amountMinor")
    private String amountMinor;

    @JsonProperty("transmissionDateTime")
    private String transmissionDateTime;

    @JsonProperty("rrn")
    private String rrn;

    @JsonProperty("rawFields")
    private Map<String, String> rawFields;
}