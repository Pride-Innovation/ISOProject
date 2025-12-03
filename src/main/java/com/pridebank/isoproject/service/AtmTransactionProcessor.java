package com.pridebank.isoproject.service;

import com.pridebank.isoproject.validation.IsoValidator;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AtmTransactionProcessor {

    private final IsoToJsonConverter isoToJsonConverter;
    private final JsonToIsoConverter jsonToIsoConverter;
    private final EsbGatewayService esbGatewayService;
    private final MessageFactory<IsoMessage> messageFactory;
    private final IsoMessageBuilder isoMessageBuilder;
    private final IsoValidator isoValidator;

    public IsoMessage processTransaction(IsoMessage isoRequest) {
        String stan = (isoRequest != null && isoRequest.hasField(11)) ?
                isoRequest.getObjectValue(11).toString() : "unknown";

        IsoValidator.ValidationResult vr = isoValidator.validate0200(isoRequest);
        if (!vr.isValid()) {
            log.warn("Validation failed - STAN: {} - {}", stan, vr.summary());
            return createErrorResponse(isoRequest, "30", truncate(vr.summary()));
        }

        try {
            assert isoRequest != null;
            String jsonRequest = isoToJsonConverter.convert(isoRequest);
            log.info("Request in JSON Format ::: {}", jsonRequest);
            String jsonResponse = esbGatewayService.sendToEsb(jsonRequest, isoRequest);
            return jsonToIsoConverter.convert(jsonResponse, isoRequest);

        } catch (Exception e) {
            log.error("Transaction failed - STAN: {}", stan, e);
            return createErrorResponse(isoRequest, "96", "System error");
        }
    }

    public IsoMessage createErrorResponse(IsoMessage request, String responseCode, String message) {
        try {
            String code = (responseCode == null || responseCode.isBlank()) ? "96" : responseCode;
            String truncated = (message == null) ? "" : truncate(message);

            if ("30".equals(code)) {
                return isoMessageBuilder.build0231(request, code, truncated);
            } else {
                IsoMessage response;
                if (request != null) {
                    int responseMti = request.getType() + 0x10;
                    response = isoMessageBuilder.createResponseFromRequest(request, responseMti);
                } else {
                    response = messageFactory.newMessage(0x0210);
                }

                response.setValue(39, code, IsoType.ALPHA, 2);
                if (!truncated.isBlank()) {
                    response.setValue(44, truncated, IsoType.LLVAR, truncated.length());
                }

                return response;
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to create error response", e);
        }
    }

    private String truncate(String s) {
        return s.length() > 25 ? s.substring(0, 25) : s;
    }
}
