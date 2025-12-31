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
public class SourceDestinationAccounts {

    @JsonProperty("fromAccount")
    private String fromAccount;

    @JsonProperty("targetAccount")
    private String targetAccount;

}
