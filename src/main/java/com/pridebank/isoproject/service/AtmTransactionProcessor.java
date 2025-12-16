package com.pridebank.isoproject.service;

import com.pridebank.isoproject.validation.IsoValidator;
import com.solab.iso8583.CustomFieldEncoder;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.IsoValue;
import com.solab.iso8583.MessageFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.nio.CharBuffer;
import java.util.*;

/**
 * ATM transaction processor — defensive handling of IsoMessage values.
 * Builds outgoing responses that contain only the fields that were present
 * in the original request (no template defaults or ESB-only fields).
 */
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
        String stan = (isoRequest != null && safeHasField(isoRequest, 11)) ?
                safeToString(safeGetObjectValue(isoRequest, 11)) : "unknown";

        if (isoRequest == null) {
            return createErrorResponse(null, "96", "Empty request");
        }

        try {
            Map<String, Object> incomingFields = buildFieldMapForLog(isoRequest);
            log.info("ISO request fields received (numeric keys only) - MTI={} fields={}", safeGetType(isoRequest), incomingFields);
        } catch (Exception e) {
            log.warn("Failed safe logging of incoming ISO request: {}", e.getMessage());
        }

        int responseMti = safeGetType(isoRequest) + 0x10;
        IsoMessage responseTemplate;
        try {
            responseTemplate = isoMessageBuilder.createResponseFromRequest(isoRequest, responseMti);
        } catch (Exception e) {
            log.warn("Failed to create response template from builder; falling back to messageFactory", e);
            responseTemplate = messageFactory.newMessage(responseMti);
        }

        try {
            removeForbidden127Subfields(responseTemplate);
        } catch (Exception ex) {
            log.warn("Failed removing forbidden 127 subfields from template; attempting to remove 127", ex);
            try {
                Method removeFields = responseTemplate.getClass().getMethod("removeFields", int.class);
                removeFields.invoke(responseTemplate, 127);
            } catch (Exception ignore) {
            }
        }

        int mti = safeGetType(isoRequest);
        String mtiStr = String.format("%04X", mti);
        log.info("Incoming ISO request - numericType={} mtiStr={}", mti, mtiStr);

        try {
            // Network management (0800) -> echo/sign-on: return EXACTLY the fields from the request
            if ("0800".equals(mtiStr) || mti == 0x800) {
                log.info("Network message (0800) - building 0810 echo body (fields exactly as request)");

                // Allowed fields: only those present in the request (exact match)
                Set<Integer> allowed = new HashSet<>(collectPresentFields(isoRequest));

                // Build response using request values only (prefer request; do not add ESB/template-only fields)
                IsoMessage finalResp = buildResponseContainingOnlyAllowed(
                        responseMti,
                        allowed,
                        isoRequest,
                        null,
                        responseTemplate
                );

                log.info("Response MTI status :::: {}", responseMti);

                isoRequest.setType(responseMti);
                return finalResp;
            }

            boolean isReversal = "0420".equals(mtiStr) || "0430".equals(mtiStr) || mti == 0x420 || mti == 0x430;
            if (isReversal) {
                log.info("Reversal received - MTI={}", mtiStr);
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
            log.debug("Request JSON sent to ESB: {}", jsonRequest);
            String jsonResponse = esbGatewayService.sendToEsb(jsonRequest, isoRequest);

            IsoMessage esbIsoResp = jsonToIsoConverter.convert(jsonResponse, isoRequest);

            if (esbIsoResp == null) {
                log.warn("ESB converter returned null; returning SYSTEM_ERROR");
                return createErrorResponse(isoRequest, "96", "System error");
            }

            boolean isMiniStatement = isMinistatementTransaction(isoRequest);
            Set<Integer> allowed = new HashSet<>(collectPresentFields(isoRequest));
            Set<Integer> mandatory = new HashSet<>(Arrays.asList(38, 39, 54));
            if (isMiniStatement) mandatory.add(48);
            allowed.addAll(mandatory);

            IsoMessage finalResp = buildResponseContainingOnlyAllowed(
                    responseMti,
                    allowed,
                    isoRequest,
                    esbIsoResp,
                    responseTemplate
            );

            try {
                String proc = safeToString(safeGetObjectValue(isoRequest, 3)).trim();
                String txType = proc.length() >= 2 ? proc.substring(0, 2) : proc;
                String rc = safeToString(safeGetObjectValue(esbIsoResp, 39)).trim();
                if (rc.isEmpty()) rc = safeToString(safeGetObjectValue(finalResp, 39)).trim();

                // success case
                if ("00".equals(rc)) {
                    // non-ministatement types that need balance + auth
                    if (txType.equals("01") || txType.equals("21") || txType.equals("31") || txType.equals("00")) {
                        if (allowed.contains(39)) {
                            try {
                                finalResp.setValue(39, "00", IsoType.ALPHA, 2);
                            } catch (Throwable ignore) {
                            }
                        }
                        Object bal = safeGetObjectValue(esbIsoResp, 54);
                        if (bal == null) bal = safeGetObjectValue(finalResp, 54);
                        if (bal != null && allowed.contains(54)) {
                            String sval = safeToString(bal);
                            try {
                                finalResp.setValue(54, sval, IsoType.LLLVAR, Math.min(sval.length(), 999));
                            } catch (Throwable ignore) {
                            }
                        }
                        Object auth = safeGetObjectValue(esbIsoResp, 38);
                        if (auth == null) auth = safeGetObjectValue(finalResp, 38);
                        if (auth != null && allowed.contains(38)) {
                            String as = safeToString(auth);
                            try {
                                finalResp.setValue(38, as, IsoType.ALPHA, Math.min(as.length(), 12));
                            } catch (Throwable ignore) {
                            }
                        }
                    } else if (txType.equals("38")) { // ministatement
                        if (allowed.contains(39)) {
                            try {
                                finalResp.setValue(39, "00", IsoType.ALPHA, 2);
                            } catch (Throwable ignore) {
                            }
                        }
                        Object mini = safeGetObjectValue(esbIsoResp, 48);
                        if (mini == null) mini = safeGetObjectValue(finalResp, 48);
                        if (mini != null && allowed.contains(48)) {
                            String ms = safeToString(mini);
                            try {
                                finalResp.setValue(48, ms, IsoType.LLLVAR, Math.min(ms.length(), 999));
                            } catch (Throwable ignore) {
                            }
                        }
                        Object bal = safeGetObjectValue(esbIsoResp, 54);
                        if (bal == null) bal = safeGetObjectValue(finalResp, 54);
                        if (bal != null && allowed.contains(54)) {
                            String sval = safeToString(bal);
                            try {
                                finalResp.setValue(54, sval, IsoType.LLLVAR, Math.min(sval.length(), 999));
                            } catch (Throwable ignore) {
                            }
                        }
                        Object auth = safeGetObjectValue(esbIsoResp, 38);
                        if (auth == null) auth = safeGetObjectValue(finalResp, 38);
                        if (auth != null && allowed.contains(38)) {
                            String as = safeToString(auth);
                            try {
                                finalResp.setValue(38, as, IsoType.ALPHA, Math.min(as.length(), 12));
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                } else {
                    // failure: set 39 to failed response code (if allowed)
                    if (allowed.contains(39) && !rc.isBlank()) {
                        try {
                            finalResp.setValue(39, rc, IsoType.ALPHA, Math.min(rc.length(), 2));
                        } catch (Throwable ignore) {
                        }
                    }
                }

            } catch (Exception ex) {
                log.debug("Failed applying transaction-specific population rules: {}", ex.getMessage());
            }

            logIsoMessageFieldsForReturn(finalResp);

            return finalResp;

        } catch (Exception e) {
            log.error("Transaction failed - STAN: {}", stan, e);
            return createErrorResponse(isoRequest, "96", "System error");
        }
    }

    private IsoMessage buildResponseContainingOnlyAllowed(
            int responseMti,
            Set<Integer> allowed,
            IsoMessage request,
            IsoMessage preferredSource,
            IsoMessage template
    ) {
        IsoMessage resp = messageFactory.newMessage(responseMti);
        try {
            resp.setType(responseMti);
        } catch (Throwable ignore) {
        }

        // Defensive: remove any default/populated fields that the factory might have set.
        try {
            pruneResponseFields(resp, Collections.emptySet());
        } catch (Exception ignored) {
        }

        if (allowed == null || allowed.isEmpty()) return resp;

        for (Integer f : allowed) {
            if (f == null || f < 2 || f > 128) continue;
            try {
                Object val = null;
                IsoValue<?> srcIsoValue = null;

                // prefer request
                if (request != null && safeHasField(request, f)) {
                    val = safeGetObjectValue(request, f);
                    try {
                        srcIsoValue = request.getField(f);
                    } catch (Exception ignore) {
                    }
                }

                // then ESB/preferred source
                if (val == null && preferredSource != null && safeHasField(preferredSource, f)) {
                    val = safeGetObjectValue(preferredSource, f);
                    try {
                        srcIsoValue = preferredSource.getField(f);
                    } catch (Exception ignore) {
                    }
                }

                // then template metadata/value (only for encoder/type/length info or fallback)
                if (val == null && template != null && safeHasField(template, f)) {
                    val = safeGetObjectValue(template, f);
                    try {
                        srcIsoValue = template.getField(f);
                    } catch (Exception ignore) {
                    }
                }

                if (val == null) continue; // do not populate fields not present in sources

                // byte[] -> BINARY if possible
                if (val instanceof byte[]) {
                    try {
                        resp.setValue(f, val, IsoType.BINARY, ((byte[]) val).length);
                        continue;
                    } catch (Throwable ignore) {
                    }
                }

                // reuse IsoValue metadata when available
                if (srcIsoValue != null) {
                    try {
                        IsoType t = srcIsoValue.getType();
                        int length = srcIsoValue.getLength() > 0 ? srcIsoValue.getLength() : Math.max(1, safeToString(val).length());
                        @SuppressWarnings("unchecked")
                        CustomFieldEncoder<Object> enc = (CustomFieldEncoder<Object>) srcIsoValue.getEncoder();
                        if (enc != null) {
                            resp.setValue(f, val, enc, t, length);
                        } else {
                            resp.setValue(f, val, t, length);
                        }
                        continue;
                    } catch (Throwable ignore) {
                    }
                }

                // generic fallback
                String sval = safeToString(val);
                try {
                    if (f == 39 || f == 38 || f == 11 || f == 37) {
                        resp.setValue(f, sval, IsoType.ALPHA, Math.min(sval.length(), 12));
                    } else if (f == 54 || f == 48) {
                        resp.setValue(f, sval, IsoType.LLLVAR, Math.min(sval.length(), 999));
                    } else {
                        resp.setValue(f, sval, IsoType.LLVAR, Math.min(sval.length(), 999));
                    }
                } catch (Throwable t) {
                    try {
                        Method setField = resp.getClass().getMethod("setField", int.class, Object.class);
                        setField.invoke(resp, f, sval);
                    } catch (Exception ignore) {
                    }
                }
            } catch (Exception ex) {
                log.debug("Failed setting allowed field {} into final response: {}", f, ex.getMessage());
            }
        }

        // Final cleanup: ensure nothing outside allowed remains.
        try {
            pruneResponseFields(resp, allowed);
        } catch (Exception ignored) {
        }

        try {
            removeForbidden127Subfields(resp);
        } catch (Exception ignored) {
        }
        return resp;
    }

    // --- Safe logging helpers ------------------------------------------------

    private Map<String, Object> buildFieldMapForLog(IsoMessage msg) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (msg == null) return map;
        for (int f = 1; f <= 128; f++) {
            try {
                if (!msg.hasField(f)) continue;
                Object v = safeGetObjectValue(msg, f);
                if (v == null) continue;
                map.put(String.valueOf(f), safeRenderValue(v));
            } catch (Exception ex) {
                map.put(String.valueOf(f), Map.of("error", ex.getMessage()));
            }
        }
        return map;
    }

    private Object safeRenderValue(Object v) {
        try {
            if (v instanceof byte[]) {
                return Map.of("binaryBase64", Base64.getEncoder().encodeToString((byte[]) v));
            }
            if (v instanceof char[]) {
                return new String((char[]) v);
            }
            if (v instanceof Character[]) {
                StringBuilder sb = new StringBuilder(((Character[]) v).length);
                for (Character c : (Character[]) v) if (c != null) sb.append(c);
                return sb.toString();
            }
            if (v instanceof IsoMessage nested) {
                return buildFieldMapForLog(nested);
            }
            if (v instanceof CharBuffer) return v.toString();
            if (v instanceof CharSequence) return v.toString();
            return v.toString();
        } catch (ClassCastException cce) {
            try {
                return String.valueOf(v);
            } catch (Exception e) {
                return Map.of("error", "unrenderable");
            }
        }
    }

    private String safeToString(Object o) {
        if (o == null) return "";
        if (o instanceof byte[]) return Base64.getEncoder().encodeToString((byte[]) o);
        if (o instanceof char[]) return new String((char[]) o);
        return o.toString();
    }

    private Object safeGetObjectValue(IsoMessage m, int f) {
        if (m == null) return null;
        try {
            Object v = m.getObjectValue(f);
            if (v != null) return v;
        } catch (Throwable ignore) {
        }
        try {
            IsoValue<?> val = m.getField(f);
            if (val != null) return val.getValue();
        } catch (Throwable ignore) {
        }
        return null;
    }

    private int safeGetType(IsoMessage m) {
        try {
            return m.getType();
        } catch (Throwable t) {
            return 0x000;
        }
    }

    private boolean safeHasField(IsoMessage m, int f) {
        try {
            return m.hasField(f);
        } catch (Throwable t) {
            return false;
        }
    }

    private void logIsoMessageFieldsForReturn(IsoMessage msg) {
        try {
            Map<String, Object> outgoing = new LinkedHashMap<>();
            for (int f = 1; f <= 128; f++) {
                try {
                    if (!safeHasField(msg, f)) continue;
                    Object v = safeGetObjectValue(msg, f);
                    if (v == null) continue;
                    outgoing.put(String.valueOf(f), safeRenderValue(v));
                } catch (Exception ex) {
                    outgoing.put(String.valueOf(f), Map.of("error", ex.getMessage()));
                }
            }
            log.info("ISO response fields to be returned (numeric keys only) - MTI={} fields={}", safeGetType(msg), outgoing);
        } catch (Exception ignored) {
        }
    }

    private void removeForbidden127Subfields(IsoMessage msg) {
        if (msg == null) return;
        try {
            if (!safeHasField(msg, 127)) return;
            Object val127 = safeGetObjectValue(msg, 127);
            if (val127 == null) {
                try {
                    Method unset = msg.getClass().getMethod("unset", int.class);
                    unset.invoke(msg, 127);
                } catch (Exception ignored) {
                }
                return;
            }

            if (val127 instanceof IsoMessage nested) {
                try {
                    Method removeFields = nested.getClass().getMethod("removeFields", int.class);
                    removeFields.invoke(nested, 25);
                    removeFields.invoke(nested, 22);
                    return;
                } catch (Exception ignore) {
                }

                try {
                    Method unset = nested.getClass().getMethod("unset", int.class);
                    unset.invoke(nested, 25);
                    unset.invoke(nested, 22);
                    return;
                } catch (Exception ignore) {
                }

                try {
                    Method removeFieldsParent = msg.getClass().getMethod("removeFields", int.class);
                    removeFieldsParent.invoke(msg, 127);
                    return;
                } catch (Exception ignored) {
                }
            }

            if (val127 instanceof String) {
                String s = ((String) val127).trim();
                if (s.startsWith("{")) {
                    String edited = s.replaceAll("\"25\"\\s*:\\s*\"[^\"]*\"\\s*,?", "")
                            .replaceAll("\"22\"\\s*:\\s*\"[^\"]*\"\\s*,?", "")
                            .replaceAll(",\\s*}", "}");
                    try {
                        try {
                            Method setValue = msg.getClass().getMethod("setValue", int.class, Object.class, IsoType.class, int.class);
                            setValue.invoke(msg, 127, edited, IsoType.LLLVAR, Math.min(edited.length(), 999));
                            return;
                        } catch (NoSuchMethodException ignored) {
                        }
                        try {
                            Method setField = msg.getClass().getMethod("setField", int.class, Object.class);
                            setField.invoke(msg, 127, edited);
                            return;
                        } catch (NoSuchMethodException ignored) {
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            try {
                Method removeFields = msg.getClass().getMethod("removeFields", int.class);
                removeFields.invoke(msg, 127);
            } catch (Exception e) {
                try {
                    Method unset = msg.getClass().getMethod("unset", int.class);
                    unset.invoke(msg, 127);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("Failed removing forbidden 127 subfields", e);
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

                Set<Integer> allowed = new HashSet<>(collectPresentFields(request));
                allowed.add(39);
                if (!truncated.isBlank()) allowed.add(44);
                // return a clean response with only allowed fields (exact match to request plus 39/44)
                return buildResponseContainingOnlyAllowed(response.getType(), allowed, request, response, response);
//                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to create error response", e);
        }
    }

    private String truncate(String s) {
        return s.length() > 25 ? s.substring(0, 25) : s;
    }

    private Set<Integer> collectPresentFields(IsoMessage m) {
        Set<Integer> fields = new HashSet<>();
        if (m == null) return fields;
        for (int i = 1; i <= 128; i++) {
            try {
                if (m.hasField(i)) fields.add(i);
            } catch (Exception ignored) {
            }
        }
        return fields;
    }

    // pruneResponseFields is retained but not used by default policy
    private void pruneResponseFields(IsoMessage msg, Set<Integer> allowed) {
        if (msg == null) return;
        for (int f = 1; f <= 128; f++) {
            try {
                // If allowed contains f -> keep it
                if (allowed != null && allowed.contains(f)) continue;

                // Try multiple removal APIs / signatures until one succeeds
                boolean removed = false;
                Exception lastEx = null;

                try {
                    Method unset = msg.getClass().getMethod("unset", int.class);
                    unset.invoke(msg, f);
                    removed = true;
                } catch (Exception e) {
                    lastEx = e;
                }

                if (!removed) {
                    try {
                        Method removeFields = msg.getClass().getMethod("removeFields", int.class);
                        removeFields.invoke(msg, f);
                        removed = true;
                    } catch (Exception e) {
                        lastEx = e;
                    }
                }

                if (!removed) {
                    try {
                        Method removeField = msg.getClass().getMethod("removeField", int.class);
                        removeField.invoke(msg, f);
                        removed = true;
                    } catch (Exception e) {
                        lastEx = e;
                    }
                }

                if (!removed) {
                    // Some implementations expect setField(int, IsoValue) or setField(int,Object)
                    try {
                        // try setField(int, Object)
                        Method setFieldObj = msg.getClass().getMethod("setField", int.class, Object.class);
                        setFieldObj.invoke(msg, f, (Object) null);
                        removed = true;
                    } catch (Exception e) {
                        lastEx = e;
                    }
                }

                if (!removed) {
                    try {
                        // try setField(int, IsoValue)
                        Method setFieldIso = msg.getClass().getMethod("setField", int.class, IsoValue.class);
                        setFieldIso.invoke(msg, f, (IsoValue<?>) null);
                        removed = true;
                    } catch (Exception e) {
                        lastEx = e;
                    }
                }

                if (!removed) {
                    // Last resort: try setValue with empty value appropriate to common types
                    try {
                        Method setValue = msg.getClass().getMethod("setValue", int.class, Object.class, IsoType.class, int.class);
                        setValue.invoke(msg, f, "", IsoType.LLVAR, 0);
                        removed = true;
                    } catch (Exception e) {
                        lastEx = e;
                    }
                }

                if (!removed) {
                    log.debug("Unable to remove field {} from response (no supported API found). Last error: {}", f, lastEx == null ? "none" : lastEx.getMessage());
                } else {
                    // optional debug
                    log.trace("Pruned field {}", f);
                }
            } catch (Exception ex) {
                log.debug("Error pruning field {}: {}", f, ex.getMessage());
            }
        }
    }

    private boolean isMinistatementTransaction(IsoMessage req) {
        try {
            if (req == null) return false;
            if (safeHasField(req, 3)) {
                Object p = safeGetObjectValue(req, 3);
                String proc = p == null ? "" : safeToString(p);
                proc = proc.trim();
                return proc.startsWith("32") || proc.startsWith("38")
                        || proc.equalsIgnoreCase("MINISTATEMENT")
                        || proc.equalsIgnoreCase("MINI_STATEMENT");
            }
            return false;
        } catch (Exception ignore) {
            return false;
        }
    }
}
