package com.pridebank.isoproject.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pridebank.isoproject.dto.AtmTransactionResponse;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.IsoValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class JsonToIsoConverter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IsoMessageBuilder isoMessageBuilder;
    private static final int MINI_STATEMENT_MAX_RECORDS = 10;
    private static final int LLLVAR_MAX = 999;

    public IsoMessage convert(String jsonResponse, IsoMessage request) throws Exception {
        AtmTransactionResponse resp = objectMapper.readValue(jsonResponse, AtmTransactionResponse.class);

        String origCode = resp.getResponseCode();
        String code = (origCode == null) ? "96" : origCode.trim();
        if (!code.matches("\\d{2}")) code = mapTextToIso39(code);

        String auth = resp.getAuthorizationCode() != null && !resp.getAuthorizationCode().isBlank()
                ? resp.getAuthorizationCode()
                : resp.getApprovalCode();

        if ("SYSTEM_ERROR".equalsIgnoreCase(origCode) || "96".equals(code)) {
            IsoMessage error = isoMessageBuilder.createResponseFromRequest(request, request.getType() + 0x10);
            error.setValue(39, "96", IsoType.ALPHA, 2);
            String msg = resp.getMessage() == null ? "SYSTEM_ERROR" : resp.getMessage();
            error.setValue(44, msg, IsoType.LLVAR, Math.min(msg.length(), 25));
            return error;
        }

        IsoMessage response = isoMessageBuilder.createResponseFromRequest(request, request.getType() + 0x10);

        response.setValue(39, code, IsoType.ALPHA, 2);
        if (auth != null && !auth.isBlank()) {
            String ac = auth.length() > 6 ? auth.substring(0, 6) : String.format("%-6s", auth);
            response.setValue(38, ac, IsoType.ALPHA, 6);
        }

        if (resp.getTransactionId() != null && !resp.getTransactionId().isBlank()) {
            String rrn = resp.getTransactionId();
            if (rrn.length() > 12) rrn = rrn.substring(0, 12);
            response.setValue(37, rrn, IsoType.ALPHA, 12);
        }

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

        String amtMinor = null;
        if (resp.getAmountMinor() != null && !resp.getAmountMinor().isBlank()) {
            amtMinor = digitsOnly(resp.getAmountMinor());
        } else if (resp.getAmount() != null) {
            amtMinor = formatMinor(resp.getAmount());
        }
        if (amtMinor != null && !amtMinor.isBlank()) {
            if (amtMinor.length() > 12) amtMinor = amtMinor.substring(0, 12);
            response.setValue(4, String.format("%012d", Long.parseLong(amtMinor)), IsoType.NUMERIC, 12);
        }

        if (resp.getCurrency() != null && !resp.getCurrency().isBlank()) {
            String curr = resp.getCurrency().trim();
            if (curr.matches("\\d+")) {
                response.setValue(49, curr, IsoType.NUMERIC, Math.min(curr.length(), 3));
            } else {
                response.setValue(49, curr.substring(0, Math.min(3, curr.length())), IsoType.ALPHA, Math.min(curr.length(), 3));
            }
        }

        // Field 54: Additional Amounts (always emit ledger + available when at least one is present)
        BigDecimal ledger = resp.getLedgerBalance();
        BigDecimal available = resp.getAvailableBalance();
        if (ledger != null || available != null) {
            String curr3 = deriveCurrency3(request, resp.getCurrency());

            // Fallback policy: if one is missing, duplicate the other so both segments are present
            BigDecimal ledgerUse = (ledger != null) ? ledger : available;
            BigDecimal availUse = (available != null) ? available : ledger;

            String segLedger = buildSeg("01", curr3, ledgerUse); // 00 acct type + 01 ledger
            String segAvail = buildSeg("02", curr3, availUse);  // 00 acct type + 02 available

            String content = segLedger + segAvail;               // 40 chars total
            String capped = content.length() > 120 ? content.substring(0, 120) : content;
            response.setValue(54, capped, IsoType.LLLVAR, capped.length());
            log.info("Field 54 formatted: len={} value={}", capped.length(), capped);
        }

        boolean isMiniReq = isMinistatementRequest(request);
        if (resp.getMiniStatementText() != null && !resp.getMiniStatementText().isBlank()) {
            String ms = resp.getMiniStatementText();
            String msTrunc = ms.length() > LLLVAR_MAX ? ms.substring(0, LLLVAR_MAX) : ms;
            if (isMiniReq) response.setValue(48, msTrunc, IsoType.LLLVAR, msTrunc.length());
            else response.setValue(62, msTrunc, IsoType.LLLVAR, msTrunc.length());
        } else if (resp.getMiniStatement() != null && !resp.getMiniStatement().isEmpty()) {
            String msText = buildMiniStatementText(resp.getMiniStatement());
            String msTrunc = msText.length() > LLLVAR_MAX ? msText.substring(0, LLLVAR_MAX) : msText;
            if (isMiniReq) response.setValue(48, msTrunc, IsoType.LLLVAR, msTrunc.length());
            else response.setValue(62, msTrunc, IsoType.LLLVAR, msTrunc.length());
        }

        if (resp.getMessage() != null && !resp.getMessage().isBlank()) {
            response.setValue(44, resp.getMessage(), IsoType.LLVAR, Math.min(resp.getMessage().length(), 25));
        }

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

        if (resp.getFromAccount() != null && !resp.getFromAccount().isBlank()) {
            response.setValue(102, resp.getFromAccount(), IsoType.LLVAR, Math.min(resp.getFromAccount().length(), 28));
        }
        if (resp.getToAccount() != null && !resp.getToAccount().isBlank()) {
            response.setValue(103, resp.getToAccount(), IsoType.LLVAR, Math.min(resp.getToAccount().length(), 28));
        }

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
                        String vToSet = val.length() > LLLVAR_MAX ? val.substring(0, LLLVAR_MAX) : val;
                        response.setValue(fid, vToSet, IsoType.LLLVAR, Math.min(vToSet.length(), 999));
                    }
                } catch (Exception ignored) {
                }
            }
            for (Map.Entry<Integer, Map<String, String>> en : grouped.entrySet()) {
                int parent = en.getKey();
                if (response.hasField(parent)) continue;
                String json = objectMapper.writeValueAsString(en.getValue());
                if (json.length() > LLLVAR_MAX) json = json.substring(0, LLLVAR_MAX);
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
        BigDecimal truncatedValue = value.setScale(0, RoundingMode.DOWN);
        String digits = truncatedValue.toPlainString().replaceAll("\\D", "");
        return digits.isEmpty() ? "0" : digits;
    }

    private String buildMiniStatementText(Object miniObj) {
        log.info("Mini Statement :::: {}", miniObj);
        try {
            List<Map<String, Object>> list = objectMapper.convertValue(
                    miniObj,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );
            int limit = Math.min(MINI_STATEMENT_MAX_RECORDS, list.size());

            StringBuilder sb = new StringBuilder();
            // Header + trailing tilde as per switch example
            sb.append("DATE_TIME|TRAN_AMOUNT|TRAN_TYPE|CURR_CODE~");

            for (int i = 0; i < limit; i++) {
                Map<String, Object> rec = list.get(i);

                String dateRaw = firstNonNullString(rec, "dateTime", "date", "transactionDate", "tranDate", "txnDate");
                String dt = toYyyyMMddHHmmss(dateRaw); // yyyyMMddHHmmss (default HHmmss=000000)

                String amountRaw = firstNonNullString(rec, "amount", "txnAmount", "amt");
                String amt12 = toMinor12(amountRaw);

                String dcRaw = firstNonNullString(rec, "drCrFlag");
                String dc = toDebitCreditFlag(dcRaw, amountRaw); // "D" or "C"

                String typeCode = "001 CSH"; // make configurable if needed
                String tranType = typeCode + " " + dc;

                String currRaw = firstNonNullString(rec, "currency");
                String curr3 = toCurrencyNumeric3(currRaw);

                sb.append(dt).append("|")
                        .append(amt12).append("|")
                        .append(tranType).append("|")
                        .append(curr3).append("~");

                if (sb.length() >= LLLVAR_MAX) break;
            }

            String result = sb.toString();
            if (result.length() > LLLVAR_MAX) result = result.substring(0, LLLVAR_MAX);
            return result;
        } catch (Exception e) {
            // Fallback: serialize whatever we got (still capped)
            try {
                String json = objectMapper.writeValueAsString(miniObj);
                return json.length() > LLLVAR_MAX ? json.substring(0, LLLVAR_MAX) : json;
            } catch (Exception ex) {
                return "";
            }
        }
    }

    private static String toDebitCreditFlag(String dcRaw, String amountRaw) {
        if (dcRaw != null && !dcRaw.isBlank()) {
            String f = dcRaw.trim().toUpperCase();
            if (f.startsWith("D")) return "D";
            if (f.startsWith("C")) return "C";
        }
        // Fallback by sign
        try {
            BigDecimal bd = new BigDecimal(amountRaw.replaceAll("[^0-9\\-.]", ""));
            return bd.signum() < 0 ? "D" : "C";
        } catch (Exception ignore) {
            return "D";
        }
    }


    private static String toYyyyMMddHHmmss(String input) {
        if (input == null || input.isBlank()) return "19700101000000";
        String s = input.trim();
        // dd/MM/yyyy → yyyyMMdd000000
        try {
            if (s.matches("\\d{2}/\\d{2}/\\d{4}")) {
                String dd = s.substring(0, 2);
                String MM = s.substring(3, 5);
                String yyyy = s.substring(6, 10);
                return yyyy + MM + dd + "000000";
            }
        } catch (Exception ignore) {
        }
        // yyyy-MM-ddTHH:mm:ss → yyyyMMddHHmmss
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*")) {
                String yyyy = s.substring(0, 4);
                String MM = s.substring(5, 7);
                String dd = s.substring(8, 10);
                String HH = s.substring(11, 13);
                String mm = s.substring(14, 16);
                String ss = s.substring(17, 19);
                return yyyy + MM + dd + HH + mm + ss;
            }
        } catch (Exception ignore) {
        }
        // Compact digits yyyyMMdd[HHmmss]
        String digits = s.replaceAll("\\D", "");
        if (digits.length() >= 14) return digits.substring(0, 14);
        if (digits.length() == 8) return digits + "000000";
        // Last resort
        return "19700101000000";
    }


    private static String toMinor12(String amountRaw) {
        if (amountRaw == null || amountRaw.isBlank()) return "000000000000";
        try {
            BigDecimal bd = new BigDecimal(amountRaw.replaceAll("[^0-9\\-.]", ""));
            bd = bd.setScale(2, RoundingMode.HALF_UP).movePointRight(2).abs();
            String d = bd.toPlainString().replaceAll("\\D", "");
            if (d.length() > 12) d = d.substring(d.length() - 12);
            return String.format("%012d", Long.parseLong(d));
        } catch (Exception e) {
            return "000000000000";
        }
    }

    private static String toCurrencyNumeric3(String currRaw) {
        if (currRaw == null || currRaw.isBlank()) return "800";
        String s = currRaw.trim();
        if (s.matches("\\d{3}")) return s;
        String up = s.toUpperCase();
        return switch (up) {
            case "UGX" -> "800";
            case "KES" -> "404";
            case "TZS" -> "834";
            case "RWF" -> "646";
            case "USD" -> "840";
            case "EUR" -> "978";
            case "GBP" -> "826";
            case "ZMW" -> "967";
            default -> "800";
        };
    }

    private static String firstNonNullString(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (m.containsKey(k) && m.get(k) != null) return String.valueOf(m.get(k));
        }
        return null;
    }

    private static String normalizeAmountString(String s) {
        if (s == null) return "";
        s = s.trim();
        try {
            BigDecimal bd = new BigDecimal(s.replaceAll("[^0-9\\-.]", ""));
            bd = bd.setScale(2, RoundingMode.HALF_UP);
            return bd.toPlainString();
        } catch (Exception ignore) {
            return s;
        }
    }

    private boolean isMinistatementRequest(IsoMessage request) {
        if (request == null) return false;
        try {
            if (!request.hasField(3)) return false;
            Object p = null;
            try {
                p = request.getObjectValue(3);
            } catch (Throwable ignore) {
            }
            if (p == null) {
                try {
                    IsoValue<?> v = request.getField(3);
                    if (v != null) p = v.getValue();
                } catch (Throwable ignored) {
                }
            }
            if (p == null) return false;
            String proc = p.toString().trim();
            return proc.startsWith("32") || proc.startsWith("38")
                    || proc.equalsIgnoreCase("MINISTATEMENT")
                    || proc.equalsIgnoreCase("MINI_STATEMENT");
        } catch (Throwable ignore) {
            return false;
        }
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
            default -> "96";
        };
    }

    // --- Field 54 helpers ---

    // Prefer ESB currency, else request field 49, else "800" (3-digit numeric)
    private String deriveCurrency3(IsoMessage request, String respCurrency) {
        String d = null;
        if (respCurrency != null && !respCurrency.isBlank()) d = respCurrency.replaceAll("[^0-9]", "");
        if ((d == null || d.isBlank()) && request != null && request.hasField(49)) {
            try {
                Object v = request.getObjectValue(49);
                if (v == null) {
                    IsoValue<?> iv = request.getField(49);
                    if (iv != null) v = iv.getValue();
                }
                if (v != null) d = v.toString().replaceAll("[^0-9]", "");
            } catch (Throwable ignore) {
            }
        }
        if (d == null || d.isBlank()) d = "800";
        if (d.length() >= 3) return d.substring(0, 3);
        return String.format("%03d", Integer.parseInt(d));
    }

    // Amount to 12 digits (abs, drop decimals, cap last 12, left-pad)
    private static String fmt12(BigDecimal value) {
        BigDecimal v = (value == null) ? BigDecimal.ZERO : value.abs().setScale(0, RoundingMode.DOWN);
        String digits = v.toPlainString().replaceAll("\\D", "");
        if (digits.isEmpty()) digits = "0";
        if (digits.length() > 12) digits = digits.substring(digits.length() - 12);
        return String.format("%012d", Long.parseLong(digits));
    }

    // 20-char segment: 1-2 acctType=00, 3-4 amtType, 5-7 currency, 8 sign, 9-20 amount(12)
    private static String buildSeg(String amtType, String curr3, BigDecimal value) {
        char sign = (value != null && value.signum() < 0) ? 'D' : 'C';
        return "00" + amtType + curr3 + sign + fmt12(value);
    }
}