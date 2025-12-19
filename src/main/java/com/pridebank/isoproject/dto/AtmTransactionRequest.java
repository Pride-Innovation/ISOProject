package com.pridebank.isoproject.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
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

    /*
        Fields for Testing ESB requests
     */

    @JsonProperty("currency")
    private String currency = "UGX";

    @JsonProperty("externalRef")
    private String externalRef;

    @JsonProperty("fee")
    private Integer fee = 0;

    @JsonProperty("narration")
    private String narration = "Test ATM Narration";

    @JsonProperty("phoneNo")
    private String phoneNo = "0779653215";

    @JsonProperty("serviceId")
    private Integer serviceId = 110;

    @JsonProperty("targetAccount")
    private String targetAccount = "212206047427801";

    /*
        Charges
     */

    @JsonProperty("charges")
    private List<Charge> charges;

    @JsonProperty("commission")
    private Commission commission;
}