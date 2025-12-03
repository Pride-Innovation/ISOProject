package com.pridebank.isoproject.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pridebank.isoproject.dto.AtmTransactionResponse;
import com.solab.iso8583.IsoMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class JsonToIsoConverter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IsoMessageBuilder isoMessageBuilder;

    public IsoMessage convert(String jsonResponse, IsoMessage request) throws Exception {
        AtmTransactionResponse resp = objectMapper.readValue(jsonResponse, AtmTransactionResponse.class);
        String code = resp.getResponseCode() == null ? "96" : resp.getResponseCode();
        String approval = resp.getApprovalCode();
        // If error-like codes map to error MTI if needed
        if ("SYSTEM_ERROR".equalsIgnoreCase(code) || "96".equals(code)) {
            IsoMessage error = isoMessageBuilder.createResponseFromRequest(request, request.getType() + 0x10);
            error.setValue(39, "96", com.solab.iso8583.IsoType.ALPHA, 2);
            error.setValue(44, resp.getMessage(),
                    com.solab.iso8583.IsoType.LLVAR,
                    Math.min(resp.getMessage() == null ? 0 : resp.getMessage().length(), 25));
            return error;
        } else {
            return isoMessageBuilder.build0210(request, code, approval);
        }
    }
}