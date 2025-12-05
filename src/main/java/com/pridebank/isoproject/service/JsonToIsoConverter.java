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
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class JsonToIsoConverter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IsoMessageBuilder isoMessageBuilder;

    public IsoMessage convert(String jsonResponse, IsoMessage request) throws Exception {
        AtmTransactionResponse resp = objectMapper.readValue(jsonResponse, AtmTransactionResponse.class);

        // determine response code
        String code = resp.getResponseCode() == null ? "96" : resp.getResponseCode();
        // prefer authorizationCode then approvalCode
        String auth = resp.getAuthorizationCode() != null && !resp.getAuthorizationCode().isBlank()
                ? resp.getAuthorizationCode()
                : resp.getApprovalCode();

        // map system error -> 96
        if ("SYSTEM_ERROR".equalsIgnoreCase(code) || "96".equals(code)) {
            IsoMessage error = isoMessageBuilder.createResponseFromRequest(request, request.getType() + 0x10);
            error.setValue(39, "96", IsoType.ALPHA, 2);
            String msg = resp.getMessage() == null ? "SYSTEM_ERROR" : resp.getMessage();
            error.setValue(44, msg, IsoType.LLVAR, Math.min(msg.length(), 25));
            return error;
        }

        // build base 0210 using builder (echoes many request fields)
        IsoMessage response = isoMessageBuilder.build0210(request, code, auth);

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
            response.setValue(11, String.format("%06d", Integer.parseInt(stan)), IsoType.NUMERIC, 6);
        }

        // Amount -> field 4 (prefer amountMinor string, else amount BigDecimal)
        String amtMinor = null;
        if (resp.getAmountMinor() != null && !resp.getAmountMinor().isBlank()) {
            amtMinor = digitsOnly(resp.getAmountMinor());
        } else if (resp.getAmount() != null) {
            amtMinor = formatMinor(resp.getAmount());
        }
        if (amtMinor != null) {
            if (amtMinor.length() > 12) amtMinor = amtMinor.substring(amtMinor.length() - 12);
            response.setValue(4, String.format("%012d", Long.parseLong(amtMinor)), IsoType.NUMERIC, 12);
        }

        // Currency -> field 49
        if (resp.getCurrency() != null && !resp.getCurrency().isBlank()) {
            response.setValue(49, resp.getCurrency(), IsoType.NUMERIC, Math.min(resp.getCurrency().length(), 3));
        }

        // Balances -> field 54 (format: AVAIL=<minor>|LEDGER=<minor>)
        if (resp.getAvailableBalance() != null || resp.getLedgerBalance() != null) {
            String avail = formatMinor(resp.getAvailableBalance());
            String ledger = formatMinor(resp.getLedgerBalance());
            String bal = "AVAIL=" + avail + "|LEDGER=" + ledger;
            response.setValue(54, bal, IsoType.LLLVAR, Math.min(bal.length(), 120));
        }

        // Mini-statement -> field 62: prefer miniStatementText else JSON array string
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

        // Ensure authorization in field 38
        if (auth != null && !auth.isBlank()) {
            String ac = auth.length() > 6 ? auth.substring(0, 6) : String.format("%-6s", auth);
            response.setValue(38, ac, IsoType.ALPHA, 6);
        }

        // MAC -> field 64 (base64)
        if (resp.getMacBase64() != null && !resp.getMacBase64().isBlank()) {
            try {
                byte[] mac = Base64.getDecoder().decode(resp.getMacBase64());
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
        if (raw != null) {
            for (Map.Entry<String, String> e : raw.entrySet()) {
                try {
                    int fid = Integer.parseInt(e.getKey());
                    String val = e.getValue();
                    if (val == null) continue;
                    if (response.hasField(fid)) continue;
                    if (fid == 64) {
                        try {
                            byte[] b = Base64.getDecoder().decode(val);
                            response.setValue(64, b, IsoType.BINARY, b.length);
                            continue;
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    response.setValue(fid, val, IsoType.LLLVAR, Math.min(val.length(), 999));
                } catch (Exception ignored) {
                }
            }
        }

        return response;
    }

    private static String digitsOnly(String s) {
        if (s == null) return null;
        String d = s.replaceAll("[^0-9]", "");
        return d.isEmpty() ? "0" : d;
    }

    // convert BigDecimal major units to minor unit string (no decimals), padded/truncated as needed
    private static String formatMinor(BigDecimal value) {
        if (value == null) return "0";
        BigDecimal minor = value.movePointRight(2).setScale(0, RoundingMode.HALF_UP);
        String digits = minor.toPlainString().replaceAll("\\D", "");
        return digits.isEmpty() ? "0" : digits;
    }
}