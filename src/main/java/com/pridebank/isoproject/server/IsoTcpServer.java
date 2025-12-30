package com.pridebank.isoproject.server;

import com.pridebank.isoproject.service.AtmTransactionProcessor;
import com.solab.iso8583.CustomFieldEncoder;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.IsoValue;
import com.solab.iso8583.MessageFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jpos.iso.ISOComponent;
import org.jpos.iso.ISOFieldPackager;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.ISOMsgFieldPackager;
import org.jpos.iso.ISOPackager;
import org.jpos.iso.packager.GenericPackager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class IsoTcpServer {
    private final MessageFactory<IsoMessage> messageFactory;
    private final AtmTransactionProcessor processor;
    private final GenericPackager jposPackager;

    @Value("${atm.server.port:7790}")
    private int port;

    @Value("${atm.server.threads:20}")
    private int threads;

    @Value("${atm.server.socket.timeout:300000}")
    private int socketTimeoutMs;

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private ExecutorService acceptLoop;

    @PostConstruct
    public void start() throws Exception {
        if (jposPackager != null) {
            log.info("jPOS GenericPackager injected and ready");
            try {
                ISOFieldPackager fp127 = jposPackager.getFieldPackager(127);
                log.info("Field 127 packager type: {}", (fp127 != null ? fp127.getClass().getName() : "null"));
                if (fp127 instanceof ISOMsgFieldPackager) {
                    ISOPackager sub = ((ISOMsgFieldPackager) fp127).getISOMsgPackager();
                    log.info("Field 127 sub-packager: {}", (sub != null ? sub.getClass().getName() : "null"));
                } else {
                    log.warn("Field 127 is not composite (ISOMsgFieldPackager). 127.* subfields will not decode/pack.");
                }
            } catch (Exception e) {
                log.warn("Could not inspect field 127 packager: {}", e.toString());
            }
        } else {
            log.warn("No jPOS GenericPackager bean; server will fall back to solab packing");
        }

        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid ATM server port: " + port);
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        pool = Executors.newFixedThreadPool(threads);
        acceptLoop = Executors.newSingleThreadExecutor();
        log.info("ISO-8583 TCP server listening on port {}", port);

        acceptLoop.submit(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    client.setSoTimeout(socketTimeoutMs);
                    pool.submit(() -> handleClient(client));
                } catch (Exception e) {
                    if (!serverSocket.isClosed()) {
                        log.error("Accept error", e);
                    }
                }
            }
        });
    }

    private final SimpleDateFormat F7 = new SimpleDateFormat("MMddHHmmss");
    private final SimpleDateFormat F12 = new SimpleDateFormat("HHmmss");
    private final SimpleDateFormat F13 = new SimpleDateFormat("MMdd");

    private void handleClient(Socket client) {
        String remote = client.getRemoteSocketAddress().toString();
        try (Socket c = client;
             InputStream in = c.getInputStream();
             OutputStream out = c.getOutputStream()) {

            while (true) {
                byte[] lenBytes = in.readNBytes(2);
                if (lenBytes.length < 2) {
                    log.info("Connection closed by {}", remote);
                    break;
                }
                int msgLen = ((lenBytes[0] & 0xff) << 8) | (lenBytes[1] & 0xff);
                byte[] payload = in.readNBytes(msgLen);
                if (payload.length != msgLen) {
                    log.warn("Incomplete message from {}: expected {}, got {}", remote, msgLen, payload.length);
                    break;
                }

                try {
                    log.info("Inbound payload len={} hex:\n{}", msgLen, bytesToHex(payload));
                    IsoMessage request = messageFactory.parseMessage(payload, 0);

                    try {
                        if (request.hasField(127)) {
                            IsoValue<?> v127 = request.getField(127);
                            Object o127 = request.getObjectValue(127);
                            String type = (v127 != null) ? v127.getType().name() : (o127 != null ? o127.getClass().getSimpleName() : "null");
                            int len = (o127 instanceof byte[]) ? ((byte[]) o127).length
                                    : (o127 instanceof String) ? ((String) o127).length()
                                    : (v127 != null ? v127.getLength() : -1);
                            log.info("Solab request 127 present: type={} length={}", type, len);
                            if (o127 instanceof String s && !s.isBlank()) {
                                log.info("Solab request 127 string preview: {}", s.substring(0, Math.min(s.length(), 200)));
                            }
                        } else {
                            log.info("Solab request 127 absent after parseMessage");
                        }
                    } catch (Exception e) {
                        log.debug("Solab 127 logging failed: {}", e.getMessage());
                    }

                    ISOMsg inbound127 = null;

                    if (jposPackager != null) {
                        try {
                            ISOMsg jReq = new ISOMsg();
                            jReq.setPackager(jposPackager);
                            jReq.unpack(payload);

                            try {
                                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                PrintStream ps = new PrintStream(bos);
                                jReq.dump(ps, "");
                                ps.flush();
                                log.info("jPOS inbound full dump:\n{}", bos.toString(StandardCharsets.UTF_8));
                            } catch (Exception e) {
                                log.debug("Failed jPOS inbound dump: {}", e.getMessage());
                            }

                            ISOPackager sub = getF127SubPackager(jposPackager);

                            if (jReq.hasField(127)) {
                                byte[] b127 = null;
                                ISOComponent comp127 = jReq.getComponent(127);

                                if (comp127 instanceof ISOMsg) {
                                    ISOMsg nested = (ISOMsg) comp127;
                                    inbound127 = nested;

                                    if (nested.getPackager() == null && sub != null) {
                                        nested.setPackager(sub);
                                        log.info("Set sub-packager on inbound 127 nested msg: {}", sub.getClass().getName());
                                    } else {
                                        log.info("Inbound 127 nested packager present? {}", (nested.getPackager() != null));
                                    }

                                    try {
                                        ByteArrayOutputStream bos2 = new ByteArrayOutputStream();
                                        PrintStream ps2 = new PrintStream(bos2);
                                        nested.dump(ps2, "  127.");
                                        ps2.flush();
                                        log.info("jPOS inbound 127 dump:\n{}", bos2.toString(StandardCharsets.UTF_8));
                                    } catch (Exception e) {
                                        log.debug("Failed jPOS nested 127 dump: {}", e.getMessage());
                                    }

                                    try {
                                        nested.unset(22);
                                        nested.unset(25);
                                        log.info("Captured inbound composite 127; pruned 22/25");
                                    } catch (Exception ignore) {
                                    }

                                    try {
                                        b127 = (sub != null) ? nested.pack() : jReq.getBytes(127);
                                    } catch (Exception packEx) {
                                        log.debug("Nested 127 pack failed: {}", packEx.getMessage());
                                    }
                                } else {
                                    try {
                                        b127 = jReq.getBytes(127);
                                    } catch (Exception ignore) {
                                    }
                                    log.info("jPOS inbound: component 127 is {}",
                                            (comp127 != null ? comp127.getClass().getName() : "null"));
                                }

                                int len = (b127 != null) ? b127.length : 0;
                                log.info("Inbound 127 bytes len={} hex={}", len, (b127 != null ? bytesToHex(b127) : ""));

                                if (sub != null && b127 != null && b127.length > 0) {
                                    try {
                                        ISOMsg nestedDec = new ISOMsg();
                                        nestedDec.setPackager(sub);
                                        nestedDec.unpack(b127);
                                        Map<String, Object> in127 = new LinkedHashMap<>();
                                        for (int s = 1; s <= 128; s++) {
                                            if (!nestedDec.hasField(s)) continue;
                                            Object v = nestedDec.getValue(s);
                                            String key = String.format("127.%03d", s);
                                            in127.put(key, (v instanceof byte[])
                                                    ? "B64:" + Base64.getEncoder().encodeToString((byte[]) v)
                                                    : nestedDec.getString(s));
                                        }
                                        log.info("Inbound 127 subfields: {}", in127);
                                    } catch (Exception e) {
                                        log.debug("Failed unpacking inbound 127 subfields: {}", e.getMessage());
                                    }
                                } else if (sub == null) {
                                    log.warn("No 127 sub-packager; inbound 127 preview limited to raw bytes.");
                                }

                                if (b127 != null && b127.length > 0) {
                                    request.setValue(127, b127, IsoType.BINARY, b127.length);
                                    log.debug("Injected raw 127 bytes into Solab request: len={}", b127.length);
                                }
                            } else {
                                log.info("jPOS inbound: field 127 absent");
                            }
                        } catch (Exception decodeEx) {
                            log.debug("Failed pre-decoding 127 via jPOS: {}", decodeEx.getMessage());
                        }
                    }

                    IsoMessage response = processor.processTransaction(request);
                    if (response == null) {
                        log.warn("Processor returned null response for {}", remote);
                        continue;
                    }

                    try {
                        sanitizeNumericLlFields(response);
                    } catch (Exception ex) {
                        log.debug("sanitizeNumericLlFields failed: {}", ex.getMessage());
                    }

                    try {
                        if (jposPackager != null) {
                            ISOMsg jmsg = getIsoMsg(response);

                            boolean attached127 = false;

                            for (int i = 2; i <= 128; i++) {
                                try {
                                    if (!response.hasField(i)) continue;
                                    Object val = response.getObjectValue(i);
                                    if (val == null) continue;

                                    if (i == 127) {
                                        try {
                                            IsoValue<?> v127 = response.getField(127);
                                            Object inner = (v127 != null) ? v127.getValue() : val;

                                            ISOPackager sub = getF127SubPackager(jposPackager);
                                            if (inner instanceof IsoMessage && sub != null) {
                                                try {
                                                    IsoMessage nestedSolab = (IsoMessage) inner;
                                                    ISOMsg nestedJ = new ISOMsg(127);
                                                    nestedJ.setPackager(sub);
                                                    for (int s = 1; s <= 128; s++) {
                                                        try {
                                                            if (!nestedSolab.hasField(s)) continue;
                                                            Object nv = nestedSolab.getObjectValue(s);
                                                            if (nv == null) continue;
                                                            if (nv instanceof byte[]) nestedJ.set(s, (byte[]) nv);
                                                            else nestedJ.set(s, nv.toString());
                                                        } catch (Exception ignore) {
                                                        }
                                                    }
                                                    try {
                                                        nestedJ.unset(22);
                                                        nestedJ.unset(25);
                                                    } catch (Exception ignore) {
                                                    }
                                                    try {
                                                        ByteArrayOutputStream bos3 = new ByteArrayOutputStream();
                                                        PrintStream ps3 = new PrintStream(bos3);
                                                        nestedJ.dump(ps3, "OUT 127.");
                                                        ps3.flush();
                                                        log.info("Prepared outbound composite 127 dump:\n{}", bos3.toString(StandardCharsets.UTF_8));
                                                    } catch (Exception e) {
                                                        log.debug("Failed outbound nested 127 dump: {}", e.getMessage());
                                                    }
                                                    jmsg.set(nestedJ);
                                                    attached127 = true;
                                                    log.info("Attached composite 127 via component API");
                                                    continue;
                                                } catch (Exception ignore) {
                                                }
                                            }

                                            if (inner instanceof IsoMessage) {
                                                try {
                                                    byte[] nested = ((IsoMessage) inner).writeData();
                                                    if (nested != null && nested.length > 0) {
                                                        jmsg.set(127, nested);
                                                        attached127 = true;
                                                        continue;
                                                    }
                                                } catch (Exception ignore) {
                                                }
                                            }
                                            if (inner instanceof byte[]) {
                                                byte[] b = (byte[]) inner;
                                                ISOPackager subz = getF127SubPackager(jposPackager);
                                                if (subz != null) {
                                                    try {
                                                        ISOMsg prune = new ISOMsg();
                                                        prune.setPackager(subz);
                                                        prune.unpack(b);
                                                        prune.unset(22);
                                                        prune.unset(25);
                                                        b = prune.pack();
                                                    } catch (Exception ignore) {
                                                    }
                                                } else {
                                                    log.warn("No 127 sub-packager; sending raw 127 bytes");
                                                }
                                                jmsg.set(127, b);
                                                attached127 = true;
                                                continue;
                                            }
                                            if (val instanceof byte[]) {
                                                jmsg.set(127, (byte[]) val);
                                                attached127 = true;
                                                continue;
                                            }
                                            if (val instanceof String) {
                                                String s = ((String) val).trim();
                                                if (s.matches("(?i)^[0-9A-F]+$") && (s.length() % 2 == 0)) {
                                                    byte[] b = hexToBytes(s);
                                                    jmsg.set(127, b);
                                                    attached127 = true;
                                                    continue;
                                                }
                                                jmsg.set(127, s);
                                                attached127 = true;
                                                continue;
                                            }
                                            jmsg.set(127, val.toString());
                                            attached127 = true;
                                            continue;
                                        } catch (Exception ignore) {
                                        }
                                    }

                                    if (i == 7 || i == 12 || i == 13) {
                                        String outStr = null;
                                        try {
                                            IsoValue<?> orig = response.getField(i);
                                            if (orig != null) {
                                                Object inner = orig.getValue();
                                                if (inner instanceof Date) {
                                                    if (i == 7) outStr = F7.format((Date) inner);
                                                    else if (i == 12) outStr = F12.format((Date) inner);
                                                    else outStr = F13.format((Date) inner);
                                                } else if (inner instanceof String) {
                                                    String t = ((String) inner).trim();
                                                    if (t.matches("\\d+")) outStr = t;
                                                }
                                            }
                                        } catch (Exception ignore) {
                                        }

                                        try {
                                            if (outStr == null) {
                                                if (val instanceof Date) {
                                                    if (i == 7) outStr = F7.format((Date) val);
                                                    else if (i == 12) outStr = F12.format((Date) val);
                                                    else outStr = F13.format((Date) val);
                                                } else if (val instanceof String && ((String) val).trim().matches("\\d+")) {
                                                    outStr = ((String) val).trim();
                                                } else {
                                                    Date now = new Date();
                                                    if (i == 7) outStr = F7.format(now);
                                                    else if (i == 12) outStr = F12.format(now);
                                                    else outStr = F13.format(now);
                                                }
                                            }
                                        } catch (Exception ignore) {
                                            Date now = new Date();
                                            outStr = (i == 7) ? F7.format(now) : (i == 12) ? F12.format(now) : F13.format(now);
                                        }

                                        jmsg.set(i, outStr);
                                        continue;
                                    }

                                    if (val instanceof String) {
                                        try {
                                            String cls = jposPackager.getFieldPackager(i).getClass().getSimpleName();
                                            if (cls.toUpperCase().contains("BINARY") || cls.toUpperCase().contains("IFB_BINARY") || cls.toUpperCase().contains("IFA_BINARY")) {
                                                String s = (String) val;
                                                if (s.matches("(?i)^[0-9A-F]+$") && (s.length() % 2 == 0)) {
                                                    byte[] b = hexToBytes(s);
                                                    jmsg.set(i, b);
                                                    continue;
                                                }
                                            }
                                        } catch (Exception ignore) {
                                        }
                                    }

                                    if (val instanceof byte[]) {
                                        jmsg.set(i, (byte[]) val);
                                    } else {
                                        jmsg.set(i, val.toString());
                                    }
                                } catch (Exception ex) {
                                    log.debug("Skipping field {} during jPOS conversion: {}", i, ex.getMessage());
                                }
                            }

                            if (!attached127 && inbound127 != null) {
                                try {
                                    if (inbound127.getFieldNumber() != 127) inbound127.setFieldNumber(127);
                                    if (inbound127.getPackager() == null) {
                                        ISOPackager sub = getF127SubPackager(jposPackager);
                                        if (sub != null) inbound127.setPackager(sub);
                                    }
                                    try {
                                        inbound127.unset(22);
                                        inbound127.unset(25);
                                    } catch (Exception ignore) {
                                    }
                                    jmsg.set(inbound127);
                                    log.info("Using inbound composite 127 in response");
                                } catch (Exception e) {
                                    log.debug("Failed attaching inbound 127 to response: {}", e.getMessage());
                                }
                            }

                            try {
                                ByteArrayOutputStream bosOut = new ByteArrayOutputStream();
                                PrintStream psOut = new PrintStream(bosOut);
                                jmsg.dump(psOut, "OUT ");
                                psOut.flush();
                                log.info("jPOS outbound full dump:\n{}", bosOut.toString(StandardCharsets.UTF_8));
                            } catch (Exception e) {
                                log.debug("Failed outbound full dump: {}", e.getMessage());
                            }

                            try {
                                if (jmsg.hasField(127)) {
                                    byte[] b127 = jmsg.getBytes(127);
                                    ISOPackager sub = getF127SubPackager(jposPackager);
                                    if (sub != null && b127 != null && b127.length > 0) {
                                        ISOMsg nested = new ISOMsg();
                                        nested.setPackager(sub);
                                        nested.unpack(b127);

                                        Map<String, Object> out127 = new LinkedHashMap<>();
                                        for (int s = 1; s <= 128; s++) {
                                            if (!nested.hasField(s)) continue;
                                            Object v = nested.getValue(s);
                                            String key = String.format("127.%03d", s);
                                            out127.put(key, (v instanceof byte[])
                                                    ? "B64:" + Base64.getEncoder().encodeToString((byte[]) v)
                                                    : nested.getString(s));
                                        }
                                        log.info("Outgoing 127 subfields: {}", out127);
                                    } else {
                                        log.info("Outgoing 127 raw (hex): {}", (b127 != null ? bytesToHex(b127) : ""));
                                        if (sub == null)
                                            log.warn("No 127 sub-packager; sent raw 127 bytes without subfields preview.");
                                    }
                                } else {
                                    log.warn("Outgoing 127 absent on jPOS response");
                                }
                            } catch (Exception e) {
                                log.debug("Failed logging outgoing 127 subfields: {}", e.getMessage());
                            }

                            byte[] jposBytes = jmsg.pack();
                            log.debug("jPOS-packed outgoing response length={} bytes. Hex:\n{}", jposBytes.length, bytesToHex(jposBytes));
                            log.debug("Outgoing response (base64): {}", Base64.getEncoder().encodeToString(jposBytes));

                            out.write((jposBytes.length >> 8) & 0xff);
                            out.write(jposBytes.length & 0xff);
                            out.write(jposBytes);
                            out.flush();
                        } else {
                            byte[] toSend = response.writeData();
                            out.write((toSend.length >> 8) & 0xff);
                            out.write((toSend.length) & 0xff);
                            out.write(toSend);
                            out.flush();
                        }
                    } catch (Exception sendEx) {
                        log.error("Failed to pack/send response via jPOS; attempting to send solab bytes", sendEx);
                        try {
                            byte[] toSend = response.writeData();
                            out.write((toSend.length >> 8) & 0xff);
                            out.write((toSend.length) & 0xff);
                            out.write(toSend);
                            out.flush();
                        } catch (Exception ex) {
                            log.error("Fallback send failed", ex);
                        }
                    }
                } catch (java.text.ParseException pe) {
                    IsoMessage errorResp = messageFactory.newMessage(0x210);
                    errorResp.setValue(39, "30", IsoType.ALPHA, 2);
                    byte[] respBytes = errorResp.writeData();

                    log.warn("Sending parse-error response (hex): {}", bytesToHex(respBytes));
                    out.write((respBytes.length >> 8) & 0xff);
                    out.write(respBytes.length & 0xff);
                    out.write(respBytes);
                    out.flush();
                    log.error("Parse error from {}: {}", remote, pe.getMessage(), pe);
                }
            }
        } catch (Exception e) {
            log.error("Client {} handler error", remote, e);
        }
    }

    private ISOMsg getIsoMsg(IsoMessage response) {
        ISOMsg jmsg = new ISOMsg();
        try {
            int typeInt = response.getType();
            jmsg.setMTI(String.format("%04X", typeInt));
        } catch (Throwable ignore) {
        }
        jmsg.setPackager(jposPackager);
        return jmsg;
    }

    private void sanitizeNumericLlFields(IsoMessage msg) {
        if (msg == null) return;

        Map<Integer, Integer> llnumMax = getIntegerIntegerMap();

        for (Map.Entry<Integer, Integer> e : llnumMax.entrySet()) {
            int fld = e.getKey();
            int maxLen = e.getValue();

            // Preserve Track-2 sentinel ‘D’/’=’ in field 35
            if (fld == 35) continue;

            try {
                if (fld == 70) continue;
                if (!msg.hasField(fld)) continue;

                Object raw = msg.getObjectValue(fld);
                if (raw == null) continue;

                String s = raw.toString();
                String digitsOnly = s.replaceAll("[^0-9]", "");
                if (digitsOnly.isBlank()) digitsOnly = "0";
                if (digitsOnly.length() > maxLen) digitsOnly = digitsOnly.substring(0, maxLen);

                IsoValue<?> orig = msg.getField(fld);
                if (orig != null) {
                    IsoType origType = orig.getType();
                    int lengthToUse;
                    if (origType == IsoType.LLVAR || origType == IsoType.LLLVAR || origType == IsoType.LLLLVAR) {
                        lengthToUse = digitsOnly.length();
                    } else if (origType.needsLength()) {
                        lengthToUse = orig.getLength() > 0 ? orig.getLength() : digitsOnly.length();
                    } else {
                        lengthToUse = digitsOnly.length();
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        CustomFieldEncoder<Object> enc = (CustomFieldEncoder<Object>) orig.getEncoder();
                        msg.setValue(fld, digitsOnly, enc, origType, lengthToUse);
                    } catch (Exception setEx) {
                        msg.setValue(fld, digitsOnly, IsoType.LLVAR, digitsOnly.length());
                    }
                } else {
                    msg.setValue(fld, digitsOnly, IsoType.LLVAR, digitsOnly.length());
                }

                log.debug("Sanitized field {} to digits-only length={}", fld, digitsOnly.length());
            } catch (Exception ex) {
                log.warn("Failed sanitizing field {} value: {}", fld, ex.getMessage());
            }
        }
    }

    private static Map<Integer, Integer> getIntegerIntegerMap() {
        Map<Integer, Integer> llnumMax = new java.util.HashMap<>();
        llnumMax.put(2, 19);
        llnumMax.put(32, 11);
        llnumMax.put(33, 11);
        llnumMax.put(35, 37); // kept for semantics; loop skips 35
        llnumMax.put(99, 11);
        llnumMax.put(100, 11);
        llnumMax.put(101, 17);
        llnumMax.put(102, 28);
        llnumMax.put(103, 17);
        llnumMax.put(104, 999);
        return llnumMax;
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    @PreDestroy
    public void stop() throws Exception {
        log.info("Stopping ISO-8583 TCP server...");
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        if (acceptLoop != null) acceptLoop.shutdownNow();
        if (pool != null) pool.shutdownNow();
        log.info("ISO-8583 TCP server stopped");
    }

    private ISOPackager getF127SubPackager(ISOPackager root) {
        if (!(root instanceof GenericPackager)) return null;
        try {
            ISOFieldPackager fp = ((GenericPackager) root).getFieldPackager(127);
            if (fp instanceof ISOMsgFieldPackager) {
                ISOPackager sub = ((ISOMsgFieldPackager) fp).getISOMsgPackager();
                if (sub != null) return sub;
                log.warn("127 sub-packager is null");
            } else {
                log.warn("Field 127 is not composite (ISOMsgFieldPackager). Check fields.xml.");
            }
        } catch (Exception e) {
            log.warn("Failed to fetch 127 sub-packager: {}", e.toString());
        }
        return null;
    }
}