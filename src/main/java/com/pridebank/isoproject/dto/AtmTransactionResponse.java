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
public class AtmTransactionResponse {

    // standard response
    @JsonProperty("responseCode")
    private String responseCode;    // ISO 39 (e.g. "00", "51", "96")

    @JsonProperty("message")
    private String message;         // human message -> ISO 44

    // authorization / approval
    @JsonProperty("authorizationCode")
    private String authorizationCode; // often ISO 38

    @JsonProperty("approvalCode")
    private String approvalCode;     // alternate name, accept both

    // identifiers
    @JsonProperty("stan")
    private String stan;            // ISO 11

    @JsonProperty("transactionId")
    private String transactionId;   // RRN -> ISO 37

    // amounts
    @JsonProperty("amount")
    private BigDecimal amount;      // major units (decimal) optional

    @JsonProperty("amountMinor")
    private String amountMinor;     // minor units string (preferred), e.g. "0001200000"

    // currency
    @JsonProperty("currency")
    private String currency;        // ISO 49 numeric or string

    // balances
    @JsonProperty("availableBalance")
    private BigDecimal availableBalance; // major units decimal

    @JsonProperty("ledgerBalance")
    private BigDecimal ledgerBalance;    // major units decimal

    // mini-statement
    @JsonProperty("miniStatement")
    private List<Map<String, Object>> miniStatement; // structured array (date,desc,amount,balance)

    @JsonProperty("miniStatementText")
    private String miniStatementText;   // fallback LLL string for field 62

    // accounts
    @JsonProperty("fromAccount")
    private String fromAccount;      // ISO 102

    @JsonProperty("toAccount")
    private String toAccount;        // ISO 103

    // MAC and raw diagnostic fields
    @JsonProperty("macBase64")
    private String macBase64;        // base64 for field 64

    @JsonProperty("rawFields")
    private Map<String, String> rawFields; // id -> value for arbitrary ISO fields
}