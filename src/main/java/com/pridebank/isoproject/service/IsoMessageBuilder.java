package com.pridebank.isoproject.service;

import com.pridebank.isoproject.util.StanGenerator;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

@Service
@RequiredArgsConstructor
@Slf4j
public class IsoMessageBuilder {

    private final MessageFactory<IsoMessage> messageFactory;
    private final StanGenerator stanGenerator;
    private final Clock clock;

    private static final DateTimeFormatter LOCAL_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter LOCAL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMdd");

    public IsoMessage build0200(String pan, long amount, String terminalId, String stan) {
        IsoMessage msg = messageFactory.newMessage(0x200);
        LocalDateTime now = LocalDateTime.now(clock);
        Date transmissionDate = Date.from(now.atZone(ZoneId.systemDefault()).toInstant());

        msg.setValue(2, pan, IsoType.LLVAR, pan.length());
        msg.setValue(3, "000000", IsoType.NUMERIC, 6);
        msg.setValue(4, String.format("%012d", amount), IsoType.NUMERIC, 12);
        msg.setValue(7, transmissionDate, IsoType.DATE10, 10);
        msg.setValue(11, stan, IsoType.NUMERIC, 6);
        msg.setValue(12, now.format(LOCAL_TIME_FORMAT), IsoType.NUMERIC, 6);
        msg.setValue(13, now.format(LOCAL_DATE_FORMAT), IsoType.NUMERIC, 4);
        msg.setValue(41, String.format("%-8s", terminalId), IsoType.ALPHA, 8);
        msg.setValue(49, "566", IsoType.NUMERIC, 3);

        return msg;
    }

    public IsoMessage build0210(IsoMessage request, String responseCode, String approvalCode) {
        IsoMessage response = createResponseFromRequest(request, 0x210);
        response.setValue(38, approvalCode != null ? approvalCode : "      ", IsoType.ALPHA, 6);
        response.setValue(39, responseCode, IsoType.ALPHA, 2);
        return response;
    }

    public IsoMessage build0231(IsoMessage request, String errorCode, String errorMessage) {
        IsoMessage response = createResponseFromRequest(request, 0x0231);
        response.setValue(39, errorCode, IsoType.ALPHA, 2);
        response.setValue(44, errorMessage, IsoType.LLVAR, Math.min(errorMessage.length(), 25));
        response.removeFields(38);
        return response;
    }

    public IsoMessage createResponseFromRequest(IsoMessage request, int responseMti) {
        IsoMessage response = messageFactory.newMessage(responseMti);
        if (request == null) return response;

        for (int i = 2; i <= 64; i++) {
            if (i == 38 || i == 39 || i == 44 || i == 54) {
                continue;
            }
            var field = request.getField(i);
            if (field != null) response.setField(i, field.clone());
        }
        return response;
    }
}
