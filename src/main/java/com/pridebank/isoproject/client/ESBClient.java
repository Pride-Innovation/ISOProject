package com.pridebank.isoproject.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

@Component
@FeignClient(
        name = "esb-client",
        url = "${esb.base-url}",
        configuration = ESBFeignConfig.class
)
public interface ESBClient {

    /**
     * Forward withdrawal POST request to ESB
     */
    @PostMapping(
            value = "${esb.withdrawal}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<?> WithdrawalRequestPostRequest(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody Object requestBody
    );

    /**
     * Forward Deposit POST request to ESB
     */
    @PostMapping(
            value = "${esb.deposit}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<?> DepositRequestPostRequest(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody Object requestBody
    );

    /**
     * Forward Transfer POST request to ESB
     */
    @PostMapping(
            value = "${esb.transfer}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<?> TransferRequestPostRequest(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody Object requestBody
    );

    /**
     * Forward Balance Inquiry POST request to ESB
     */
    @PostMapping(
            value = "${esb.balance-inquiry}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<?> BalanceInquiryRequestPostRequest(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody Object requestBody
    );

    /**
     * Forward Mini Statement POST request to ESB
     */
    @PostMapping(
            value = "${esb.mini-statement}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<?> MiniStatementRequestPostRequest(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody Object requestBody
    );
}