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

        // Bypass 0200 validator for network (0800) and reversal (0420)
        if (isoRequest == null) {
            return createErrorResponse(null, "96", "Empty request");
        }

        int mti = isoRequest.getType();
        try {
            if (mti == 0x800) {
                log.info("MTI decimal={} hex=0x{} human={} ", mti, Integer.toHexString(mti).toUpperCase(), String.format("%04X", mti));
                // echo / sign on - field 70 must be echoed or set; prefer request field 70
                log.info("Iso Data at 70 ::: {}", isoRequest.getObjectValue(70).toString());

                String f70 = isoRequest.hasField(70) ? isoRequest.getObjectValue(70).toString() : "001";
                log.info("f70 information :::: {}", f70);
                return isoMessageBuilder.build0810(isoRequest, f70);
            }
            if (mti == 0x420) {
                log.info("MTI decimal={} hex=0x{} human={} ", mti, Integer.toHexString(mti).toUpperCase(), String.format("%04X", mti));
                // reversal - forward to ESB or create response; apply minimal validation
                // proceed with normal flow but skip strict 0200 validation
            } else if (mti == 0x200) {
                log.info("MTI info decimal={} hex=0x{} human={} ", mti, Integer.toHexString(mti).toUpperCase(), String.format("%04X", mti));

                IsoValidator.ValidationResult vr = isoValidator.validate0200(isoRequest);
                if (!vr.isValid()) {
                    log.warn("Validation failed - STAN: {} - {}", stan, vr.summary());
                    return createErrorResponse(isoRequest, "30", truncate(vr.summary()));
                }
            }

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
