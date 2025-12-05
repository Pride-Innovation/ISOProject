package com.pridebank.isoproject.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solab.iso8583.IsoMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
//import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;

@Service
@Slf4j
public class IsoToJsonConverter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleDateFormat isoDt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
//    private final SimpleDateFormat timeOnly = new SimpleDateFormat("HHmmss");

    public String convert(IsoMessage isoMessage) throws Exception {
        ObjectNode json = objectMapper.createObjectNode();
        ObjectNode raw = objectMapper.createObjectNode();

        json.put("messageType", String.format("%04d", isoMessage.getType()));

        // helper lambda-like inline logic using methods below
        // common semantic fields
        if (isoMessage.hasField(2)) {
            String pan = toStringSafe(isoMessage.getObjectValue(2));
            json.put("cardNumber", maskPan(pan));
            json.put("accountNumber", pan);
            raw.put("2", pan);
        }
        if (isoMessage.hasField(3)) {
            String proc = toStringSafe(isoMessage.getObjectValue(3));
            json.put("processingCode", proc);
            json.put("transactionType", transactionTypeFromProc(proc));
            raw.put("3", proc);
        }
        if (isoMessage.hasField(4)) {
            String amountStr = toStringSafe(isoMessage.getObjectValue(4));
//            json.put("amountMinor", amountStr);
            json.put("amount", amountStr);
            json.put("amountValue", parseAmount(amountStr).toString());
            raw.put("4", amountStr);
        }
        if (isoMessage.hasField(7)) {
            Object v = isoMessage.getObjectValue(7);
            json.put("transmissionDateTime", formatDateLike(v));
            raw.put("7", formatDateLike(v));
        }
        if (isoMessage.hasField(11)) {
            String stan = toStringSafe(isoMessage.getObjectValue(11));
            json.put("stan", stan);
            raw.put("11", stan);
        }
        if (isoMessage.hasField(12)) {
            json.put("timeLocal", toStringSafe(isoMessage.getObjectValue(12)));
            raw.put("12", toStringSafe(isoMessage.getObjectValue(12)));
        }
        if (isoMessage.hasField(13)) {
            json.put("dateLocal", toStringSafe(isoMessage.getObjectValue(13)));
            raw.put("13", toStringSafe(isoMessage.getObjectValue(13)));
        }
        if (isoMessage.hasField(32)) {
            json.put("acquiringInstitutionId", toStringSafe(isoMessage.getObjectValue(32)));
            raw.put("32", toStringSafe(isoMessage.getObjectValue(32)));
        }
        if (isoMessage.hasField(37)) {
            json.put("rrn", toStringSafe(isoMessage.getObjectValue(37)));
            raw.put("37", toStringSafe(isoMessage.getObjectValue(37)));
        }
        if (isoMessage.hasField(38)) {
            json.put("authorizationCode", toStringSafe(isoMessage.getObjectValue(38)));
            raw.put("38", toStringSafe(isoMessage.getObjectValue(38)));
        }
        if (isoMessage.hasField(39)) {
            json.put("responseCode", toStringSafe(isoMessage.getObjectValue(39)));
            raw.put("39", toStringSafe(isoMessage.getObjectValue(39)));
        }
        if (isoMessage.hasField(41)) {
            json.put("terminalId", toStringSafe(isoMessage.getObjectValue(41)).trim());
            raw.put("41", toStringSafe(isoMessage.getObjectValue(41)));
        }
        if (isoMessage.hasField(42)) {
            json.put("merchantId", toStringSafe(isoMessage.getObjectValue(42)));
            raw.put("42", toStringSafe(isoMessage.getObjectValue(42)));
        }
        if (isoMessage.hasField(43)) {
            json.put("merchantInfo", toStringSafe(isoMessage.getObjectValue(43)));
            raw.put("43", toStringSafe(isoMessage.getObjectValue(43)));
        }
        if (isoMessage.hasField(44)) {
            json.put("additionalResponseData", toStringSafe(isoMessage.getObjectValue(44)));
            raw.put("44", toStringSafe(isoMessage.getObjectValue(44)));
        }
        if (isoMessage.hasField(49)) {
            json.put("currencyCode", toStringSafe(isoMessage.getObjectValue(49)));
            raw.put("49", toStringSafe(isoMessage.getObjectValue(49)));
        }
        // balances or long data
        if (isoMessage.hasField(54)) {
            json.put("balanceData", toStringSafe(isoMessage.getObjectValue(54)));
            raw.put("54", toStringSafe(isoMessage.getObjectValue(54)));
        }
        if (isoMessage.hasField(62)) {
            json.put("miniStatement", toStringSafe(isoMessage.getObjectValue(62)));
            raw.put("62", toStringSafe(isoMessage.getObjectValue(62)));
        }
        if (isoMessage.hasField(102)) {
            json.put("fromAccount", toStringSafe(isoMessage.getObjectValue(102)));
            raw.put("102", toStringSafe(isoMessage.getObjectValue(102)));
        }
        if (isoMessage.hasField(123)) {
            json.put("privateData", toStringSafe(isoMessage.getObjectValue(123)));
            raw.put("123", toStringSafe(isoMessage.getObjectValue(123)));
        }

        // binary fields: 55 (EMV), 64 (MAC) etc.
        if (isoMessage.hasField(55)) {
            raw.put("55", bytesToBase64(isoMessage.getObjectValue(55)));
            json.put("emvDataBase64", bytesToBase64(isoMessage.getObjectValue(55)));
        }
        if (isoMessage.hasField(64)) {
            raw.put("64", bytesToBase64(isoMessage.getObjectValue(64)));
            json.put("mac", bytesToBase64(isoMessage.getObjectValue(64)));
        }

        // capture any remaining fields into a rawFields object
        for (int i = 1; i <= 128; i++) {
            try {
                if (isoMessage.hasField(i) && !raw.has(String.valueOf(i))) {
                    Object v = isoMessage.getObjectValue(i);
                    raw.put(String.valueOf(i), toStringSafe(v));
                }
            } catch (Exception ignored) {
            }
        }

        json.set("rawFields", raw);
        return objectMapper.writeValueAsString(json);
    }

    // helpers
    private BigDecimal parseAmount(String amountStr) {
        log.info("Incoming Amount ::: {}", amountStr);
        if (amountStr == null || amountStr.isBlank()) return BigDecimal.ZERO;
        long cents = Long.parseLong(amountStr.trim());
        log.info("Cents value ::: {}", cents);
//        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP); returning decimals for cents
//        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(cents);
    }

    private String maskPan(String pan) {
        if (pan == null || pan.length() < 13) return "****";
        return pan.substring(0, 6) + "******" + pan.substring(pan.length() - 4);
    }

    private String toStringSafe(Object v) {
        if (v == null) return null;
        if (v instanceof byte[]) return bytesToBase64(v);
        if (v instanceof Date) return isoDt.format((Date) v);
        return v.toString();
    }

    private String formatDateLike(Object v) {
        if (v == null) return null;
        if (v instanceof Date) return isoDt.format((Date) v);
        // some implementations store DATE10 as String like MMddHHmmss
        String s = v.toString();
        if (s.matches("\\d{10}")) {
            try {
                // parse MMddHHmmss to today's year
                String y = new SimpleDateFormat("yyyy").format(new Date());
                return y + s.substring(0, 2) + "-" + s.substring(2, 4) + "T" + s.substring(4, 6) + ":" + s.substring(6, 8) + ":" + s.substring(8, 10);
            } catch (Exception ignored) {
            }
        }
        return s;
    }

    private String bytesToBase64(Object v) {
        try {
            if (v instanceof byte[]) return Base64.getEncoder().encodeToString((byte[]) v);
            return Base64.getEncoder().encodeToString(v.toString().getBytes());
        } catch (Exception e) {
            return v.toString();
        }
    }

    private String transactionTypeFromProc(String proc) {
        if (proc == null || proc.length() < 2) return "UNKNOWN";
        String fam = proc.substring(0, 2);
        return switch (fam) {
            case "01" -> "WITHDRAWAL";
            case "02" -> "DEPOSIT";
            case "03" -> "TRANSFER";
            case "31" -> "BALANCE_INQUIRY";
            case "32" -> "MINI_STATEMENT";
            default -> "OTHER";
        };
    }
}