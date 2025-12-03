package com.pridebank.isoproject.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AtmTransactionResponse {

    @JsonProperty("responseCode")
    private String responseCode;

    @JsonProperty("message")
    private String message;

    @JsonProperty("authorizationCode")
    private String authorizationCode;

    @JsonProperty("availableBalance")
    private BigDecimal availableBalance;

    @JsonProperty("ledgerBalance")
    private BigDecimal ledgerBalance;

    @JsonProperty("stan")
    private String stan;

    @JsonProperty("transactionId")
    private String transactionId;

    private String approvalCode;
}
