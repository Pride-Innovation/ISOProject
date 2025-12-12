package com.pridebank.isoproject.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pridebank.isoproject.dto.AtmTransactionResponse;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class JsonToIsoConverter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IsoMessageBuilder isoMessageBuilder;

    public IsoMessage convert(String jsonResponse, IsoMessage request) throws Exception {
        AtmTransactionResponse resp = objectMapper.readValue(jsonResponse, AtmTransactionResponse.class);

        String origCode = resp.getResponseCode();
        String code = (origCode == null) ? "96" : origCode.trim();
        if (!code.matches("\\d{2}")) {
            code = mapTextToIso39(code);
        }

        String auth = resp.getAuthorizationCode() != null && !resp.getAuthorizationCode().isBlank()
                ? resp.getAuthorizationCode()
                : resp.getApprovalCode();

        // map system error -> 96 (accept textual SYSTEM_ERROR as well)
        if ("SYSTEM_ERROR".equalsIgnoreCase(origCode) || "96".equals(code)) {
            IsoMessage error = isoMessageBuilder.createResponseFromRequest(request, request.getType() + 0x10);
            error.setValue(39, "96", IsoType.ALPHA, 2);
            String msg = resp.getMessage() == null ? "SYSTEM_ERROR" : resp.getMessage();
            error.setValue(44, msg, IsoType.LLVAR, Math.min(msg.length(), 25));
            return error;
        }

        // Build base response preserving request->response MTI relationship.
        // Use createResponseFromRequest so reversal responses (0420->0430) are produced correctly.
        IsoMessage response = isoMessageBuilder.createResponseFromRequest(request, request.getType() + 0x10);

        // Ensure response code and auth are set (builder may not set them)
        response.setValue(39, code, IsoType.ALPHA, 2);
        if (auth != null && !auth.isBlank()) {
            String ac = auth.length() > 6 ? auth.substring(0, 6) : String.format("%-6s", auth);
            response.setValue(38, ac, IsoType.ALPHA, 6);
        }

        // RRN -> field 37 (transactionId)
        if (resp.getTransactionId() != null && !resp.getTransactionId().isBlank()) {
            String rrn = resp.getTransactionId();
            if (rrn.length() > 12) rrn = rrn.substring(0, 12);
            response.setValue(37, rrn, IsoType.ALPHA, 12);
        }

        // STAN -> field 11
        if (resp.getStan() != null && !resp.getStan().isBlank()) {
            String stan = resp.getStan().replaceAll("\\D", "");
            if (stan.length() > 6) stan = stan.substring(stan.length() - 6);
            try {
                response.setValue(11, String.format("%06d", Integer.parseInt(stan)), IsoType.NUMERIC, 6);
            } catch (NumberFormatException nfe) {
                String padded = stan.length() >= 6 ? stan.substring(stan.length() - 6) : String.format("%06d", 0);
                response.setValue(11, padded, IsoType.NUMERIC, 6);
            }
        }

        // Amount -> field 4
        String amtMinor = null;
        if (resp.getAmountMinor() != null && !resp.getAmountMinor().isBlank()) {
            amtMinor = digitsOnly(resp.getAmountMinor());
        } else if (resp.getAmount() != null) {
            amtMinor = formatMinor(resp.getAmount());
        }
        if (amtMinor != null && !amtMinor.isBlank()) {
            if (amtMinor.length() > 12) amtMinor = amtMinor.substring(amtMinor.length() - 12);
            response.setValue(4, String.format("%012d", Long.parseLong(amtMinor)), IsoType.NUMERIC, 12);
        }

        // Currency -> field 49
        if (resp.getCurrency() != null && !resp.getCurrency().isBlank()) {
            String curr = resp.getCurrency().trim();
            if (curr.matches("\\d+")) {
                response.setValue(49, curr, IsoType.NUMERIC, Math.min(curr.length(), 3));
            } else {
                response.setValue(49, curr.substring(0, Math.min(3, curr.length())), IsoType.ALPHA, Math.min(curr.length(), 3));
            }
        }

        // Balances -> field 54
        if (resp.getAvailableBalance() != null || resp.getLedgerBalance() != null) {
            String avail = formatMinor(resp.getAvailableBalance());
            String ledger = formatMinor(resp.getLedgerBalance());
            String bal = "AVAIL=" + avail + "|LEDGER=" + ledger;
            response.setValue(54, bal, IsoType.LLLVAR, Math.min(bal.length(), 120));
        }

        // Mini-statement -> field 62
        if (resp.getMiniStatementText() != null && !resp.getMiniStatementText().isBlank()) {
            String ms = resp.getMiniStatementText();
            response.setValue(62, ms, IsoType.LLLVAR, Math.min(ms.length(), 999));
        } else if (resp.getMiniStatement() != null && !resp.getMiniStatement().isEmpty()) {
            String msText = objectMapper.writeValueAsString(resp.getMiniStatement());
            response.setValue(62, msText, IsoType.LLLVAR, Math.min(msText.length(), 999));
        }

        // Message -> field 44
        if (resp.getMessage() != null && !resp.getMessage().isBlank()) {
            response.setValue(44, resp.getMessage(), IsoType.LLVAR, Math.min(resp.getMessage().length(), 25));
        }

        // MAC -> field 64
        if (resp.getMacBase64() != null && !resp.getMacBase64().isBlank()) {
            try {
                byte[] mac = Base64.getDecoder().decode(resp.getMacBase64());
                if (mac.length != 8) {
                    byte[] mac8 = new byte[8];
                    System.arraycopy(mac, 0, mac8, 0, Math.min(mac.length, 8));
                    mac = mac8;
                }
                response.setValue(64, mac, IsoType.BINARY, mac.length);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid MAC base64 from ESB: {}", resp.getMacBase64());
            }
        }

        // fromAccount -> field 102, toAccount -> field 103
        if (resp.getFromAccount() != null && !resp.getFromAccount().isBlank()) {
            response.setValue(102, resp.getFromAccount(), IsoType.LLVAR, Math.min(resp.getFromAccount().length(), 28));
        }
        if (resp.getToAccount() != null && !resp.getToAccount().isBlank()) {
            response.setValue(103, resp.getToAccount(), IsoType.LLVAR, Math.min(resp.getToAccount().length(), 28));
        }

        // rawFields map -> set by id (string values) unless field already present
        Map<String, String> raw = resp.getRawFields();
        if (raw != null && !raw.isEmpty()) {
            Map<Integer, Map<String, String>> grouped = new HashMap<>();
            for (Map.Entry<String, String> e : raw.entrySet()) {
                String key = e.getKey();
                String val = e.getValue();
                if (val == null) continue;
                try {
                    if (key.contains(".")) {
                        String[] parts = key.split("\\.", 2);
                        int parent = Integer.parseInt(parts[0]);
                        grouped.computeIfAbsent(parent, k -> new HashMap<>()).put(parts[1], val);
                    } else {
                        int fid = Integer.parseInt(key);
                        if (response.hasField(fid)) continue;
                        if (fid == 64) {
                            try {
                                byte[] b = Base64.getDecoder().decode(val);
                                if (b.length != 8) {
                                    byte[] b8 = new byte[8];
                                    System.arraycopy(b, 0, b8, 0, Math.min(8, b.length));
                                    b = b8;
                                }
                                response.setValue(64, b, IsoType.BINARY, b.length);
                                continue;
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                        response.setValue(fid, val, IsoType.LLLVAR, Math.min(val.length(), 999));
                    }
                } catch (Exception ignored) {
                }
            }
            for (Map.Entry<Integer, Map<String, String>> en : grouped.entrySet()) {
                int parent = en.getKey();
                if (response.hasField(parent)) continue;
                String json = objectMapper.writeValueAsString(en.getValue());
                response.setValue(parent, json, IsoType.LLLVAR, Math.min(json.length(), 999));
            }
        }

        return response;
    }

    private static String digitsOnly(String s) {
        if (s == null) return null;
        String d = s.replaceAll("[^0-9]", "");
        return d.isEmpty() ? "0" : d;
    }

    private static String formatMinor(BigDecimal value) {
        if (value == null) return "0";
        BigDecimal minor = value.movePointRight(2).setScale(0, RoundingMode.HALF_UP);
        String digits = minor.toPlainString().replaceAll("\\D", "");
        return digits.isEmpty() ? "0" : digits;
    }

    private String mapTextToIso39(String text) {
        if (text == null) return "96";
        String up = text.trim().toUpperCase();
        return switch (up) {
            case "OK", "SUCCESS", "APPROVED", "APPROVAL" -> "00";
            case "INSUFFICIENT_FUNDS", "INSUFFICIENT FUNDS", "NOT_ENOUGH_FUNDS" -> "51";
            case "INVALID_ACCOUNT", "ACCOUNT_NOT_FOUND", "NO_ACCOUNT" -> "14";
            case "EXCEEDS_LIMIT", "LIMIT_EXCEEDED" -> "61";
            case "AUTH_FAILED", "DECLINED" -> "05";
            case "DUPLICATE" -> "94";
            case "TIMEOUT", "UNAVAILABLE", "SERVICE_UNAVAILABLE" -> "96";
            default -> "96";
        };
    }
}