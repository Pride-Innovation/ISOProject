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

        if (isoRequest == null) {
            return createErrorResponse(null, "96", "Empty request");
        }

        int mti = isoRequest.getType();
        String mtiStr = String.format("%04X", mti); // "0200","0420","0430","0800" etc.
        log.info("Incoming ISO request - numericType={} mtiStr={}", mti, mtiStr);

        try {
            if ("0800".equals(mtiStr) || mti == 0x800) {
                log.info("Network message (0800) - field70={}", isoRequest.hasField(70) ? isoRequest.getObjectValue(70) : "N/A");
                String f70 = isoRequest.hasField(70) ? isoRequest.getObjectValue(70).toString() : "001";
                return isoMessageBuilder.build0810(isoRequest, f70);
            }

            boolean isReversal = "0420".equals(mtiStr) || "0430".equals(mtiStr) || mti == 0x420 || mti == 0x430;
            if (isReversal) {
                log.info("Reversal received - MTI={}", mtiStr);

                // Minimal reversal logging
                Object f11 = isoRequest.hasField(11) ? isoRequest.getObjectValue(11) : null;
                Object f37 = isoRequest.hasField(37) ? isoRequest.getObjectValue(37) : null;
                Object f90 = isoRequest.hasField(90) ? isoRequest.getObjectValue(90) : null;
                Object f95 = isoRequest.hasField(95) ? isoRequest.getObjectValue(95) : null;
                Object f127 = isoRequest.hasField(127) ? isoRequest.getObjectValue(127) : null;

                log.info("Reversal fields summary - STAN(11)={}, RRN(37)={}, 90={}, 95={}, 127={}",
                        f11, f37, f90, f95, f127);

                // Skip 0200 validation for reversals; continue to ESB forwarding path below
            } else if ("0200".equals(mtiStr) || mti == 0x200) {
                log.info("Purchase (0200) received - performing 0200 validation");
                IsoValidator.ValidationResult vr = isoValidator.validate0200(isoRequest);
                if (!vr.isValid()) {
                    log.warn("Validation failed - STAN: {} - {}", stan, vr.summary());
                    return createErrorResponse(isoRequest, "30", truncate(vr.summary()));
                }
            } else {
                log.info("Unhandled MTI {}; continuing (no 0200 validation applied)", mtiStr);
            }

            String jsonRequest = isoToJsonConverter.convert(isoRequest);
            log.info("Request in JSON Format ::: {}", jsonRequest);

            // Use same ESB path for testing for both reversals and purchases
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
                    int responseMti = request.getType() + 0x10; // keep response MTI aligned with request
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