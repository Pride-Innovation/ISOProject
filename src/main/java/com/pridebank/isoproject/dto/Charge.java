package com.pridebank.isoproject.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Charge {

    @JsonProperty("amount")
    private Integer amount = 100;

    @JsonProperty("description")
    private String description = "VAT";

    @JsonProperty("toAccount")
    private String toAccount = "212206047427801";
}
