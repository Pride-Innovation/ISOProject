package com.pridebank.isoproject.dto;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@Data
@RequiredArgsConstructor
public class BalanceEnquiryResponse {
    private String firstName;
    private String lastName;
    private BigDecimal amount;
}
