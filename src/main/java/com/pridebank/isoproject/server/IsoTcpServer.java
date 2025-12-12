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
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class IsoTcpServer {

    private final MessageFactory<IsoMessage> messageFactory;
    private final AtmTransactionProcessor processor;

    @Value("${atm.server.port:7790}")
    private int port;

    @Value("${atm.server.threads:20}")
    private int threads;

    @Value("${atm.server.socket.timeout:300000}")
    private int socketTimeoutMs;

    // jPOS packager for wire-compatible packing (uses same fields.xml as client)
    private GenericPackager jposPackager;

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private ExecutorService acceptLoop;

    @PostConstruct
    public void start() throws Exception {
        // load jPOS packager first (server-side compatibility shim)
        try (InputStream is = getClass().getResourceAsStream("/packager/fields.xml")) {
            if (is == null) {
                log.warn("jPOS packager resource /packager/fields.xml not found on classpath; jPOS packing disabled");
            } else {
                jposPackager = new GenericPackager(is);
                log.info("Loaded jPOS GenericPackager from /packager/fields.xml");
            }
        } catch (Exception e) {
            log.warn("Failed loading jPOS GenericPackager: {}", e.getMessage());
            jposPackager = null;
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
                int msgLen = ((lenBytes[0] & 0xFF) << 8) | (lenBytes[1] & 0xFF);
                byte[] payload = in.readNBytes(msgLen);
                if (payload.length != msgLen) {
                    log.warn("Incomplete message from {}: expected {}, got {}", remote, msgLen, payload.length);
                    break;
                }

                try {
                    IsoMessage request = messageFactory.parseMessage(payload, 0);
                    log.info("Parsed Request ::: {}", request);
                    IsoMessage response = processor.processTransaction(request);

                    if (response == null) {
                        log.warn("Processor returned null response for {}", remote);
                        continue;
                    }

                    log.info("Transaction Response (IsoMessage) ::: {}", Optional.ofNullable(response.getObjectValue(70)));

                    // Log details BEFORE sanitize
                    try {
                        boolean has70 = response.hasField(70);
                        Object obj70 = has70 ? response.getObjectValue(70) : null;
                        String typeLen = "n/a";
                        try {
                            var f70 = response.getField(70);
                            if (f70 != null) typeLen = f70.getType() + "/" + f70.getLength();
                        } catch (Exception ignore) {
                        }
                        log.info("Response BEFORE sanitize - field70 present={} value='{}' type/len={}", has70, obj70, typeLen);

                        StringBuilder sb = new StringBuilder();
                        for (int i = 2; i <= 128; i++) {
                            if (response.hasField(i)) {
                                var fld = response.getField(i);
                                Object val = response.getObjectValue(i);
                                sb.append(i)
                                        .append("[")
                                        .append(fld != null ? fld.getType() : "null")
                                        .append("/")
                                        .append(fld != null ? fld.getLength() : "n/a")
                                        .append("]=")
                                        .append(val)
                                        .append("; ");
                            }
                        }
                        log.debug("Response BEFORE sanitize - fields dump: {}", sb.toString());
                    } catch (Exception ex) {
                        log.warn("Unable to log response details BEFORE sanitize: {}", ex.getMessage());
                    }

                    // Sanitize numeric LL fields that the client packager expects as digits-only
                    try {
                        log.info("Still checking Field 70 ::: {}", Optional.ofNullable(response.getObjectValue(70)));
                        sanitizeNumericLlFields(response);
                    } catch (Exception ex) {
                        log.debug("sanitizeNumericLlFields failed: {}", ex.getMessage());
                    }

                    // Remove high-numbered fields (99..128) to avoid client unpack mismatches
                    try {
                        for (int f = 99; f <= 128; f++) {
                            if (response.hasField(f)) {
                                response.removeFields(f);
                                log.debug("Removed field {} from outgoing response to avoid client unpack issues", f);
                            }
                        }
                    } catch (Exception ex) {
                        log.debug("Failed removing high-numbered fields before send: {}", ex.getMessage());
                    }

                    byte[] respBytesSolab = null;
                    try {
                        Object post70 = response.getObjectValue(70);
                        log.info("Response AFTER sanitize - field70 object='{}'", post70);
                        var f70 = response.getField(70);
                        log.info("Response AFTER sanitize - field70 type/len={}/{}", f70 != null ? f70.getType() : "null", f70 != null ? f70.getLength() : "n/a");

                        // Force field 70 to be NUMERIC(3) just before packing to avoid template/default interference
                        try {
                            Object val70 = response.getObjectValue(70);
                            if (val70 != null) {
                                String raw = val70.toString().trim();
                                int intval = Integer.parseInt(raw);
                                String padded = String.format("%03d", intval);
                                response.setValue(70, padded, IsoType.NUMERIC, 3);
                                log.debug("Forced field70 as NUMERIC(3): '{}'", padded);
                            }
                        } catch (NumberFormatException nfe) {
                            try {
                                Object val70 = response.getObjectValue(70);
                                if (val70 != null) {
                                    String s = val70.toString();
                                    String outStr = s.length() >= 3 ? s.substring(0, 3) : String.format("%-3s", s);
                                    response.setValue(70, outStr, IsoType.ALPHA, 3);
                                    log.debug("Forced field70 fallback as ALPHA(3): '{}'", outStr);
                                }
                            } catch (Exception ignore) {
                            }
                        } catch (Exception ex) {
                            log.debug("Could not enforce field70 numeric: {}", ex.getMessage());
                        }

                        // keep solab-packed bytes for debug
                        try {
                            respBytesSolab = response.writeData();
                            log.debug("Solab-packed outgoing response length={} bytes. Hex:\n{}", respBytesSolab.length, bytesToHex(respBytesSolab));
                            log.debug("Outgoing response info (base64): {}", Base64.getEncoder().encodeToString(respBytesSolab));
                        } catch (Exception ex) {
                            log.debug("Solab writeData() failed: {}", ex.getMessage());
                        }
                    } catch (Exception dbg) {
                        log.warn("Pre-send debug failed: {}", dbg.getMessage());
                    }

                    // Now convert to jPOS ISOMsg and pack with jPOS packager for compatibility if available
                    try {
                        if (jposPackager != null) {
                            ISOMsg jmsg = getIsoMsg(response);

                            // copy fields 2..128 with minimal conversions:
                            for (int i = 2; i <= 128; i++) {
                                try {
                                    if (!response.hasField(i)) continue;
                                    Object val = response.getObjectValue(i);
                                    if (val == null) continue;

                                    // Special-case date/time fields so jPOS sees the expected numeric strings
                                    if (val instanceof Date) {
                                        String s;
                                        if (i == 7) s = F7.format((Date) val);
                                        else if (i == 12) s = F12.format((Date) val);
                                        else if (i == 13) s = F13.format((Date) val);
                                        else s = val.toString();
                                        jmsg.set(i, s);
                                        continue;
                                    }

                                    // If solab stored numeric/longs etc, stringify
                                    if (val instanceof Number || !(val instanceof byte[])) {
                                        // binary fields in jPOS expect byte[]; try to detect common binary field ids
                                        if (val instanceof String) {
                                            // if target packager expects binary, try to convert hex string to bytes
                                            try {
                                                String cls = jposPackager.getFieldPackager(i).getClass().getSimpleName();
                                                if (cls.toUpperCase().contains("BINARY") || cls.toUpperCase().contains("IFB_BINARY") || cls.toUpperCase().contains("IFA_BINARY")) {
                                                    String s = (String) val;
                                                    // treat as hex if it looks hexy
                                                    if (s.matches("(?i)^[0-9A-F]+$") && (s.length() % 2 == 0)) {
                                                        int len = s.length() / 2;
                                                        byte[] b = new byte[len];
                                                        for (int k = 0; k < len; k++) {
                                                            b[k] = (byte) Integer.parseInt(s.substring(k * 2, k * 2 + 2), 16);
                                                        }
                                                        jmsg.set(i, b);
                                                        continue;
                                                    }
                                                }
                                            } catch (Exception ignore) {
                                                // fallback to string
                                            }
                                        }
                                        jmsg.set(i, val.toString());
                                    } else if (val instanceof byte[]) {
                                        jmsg.set(i, (byte[]) val);
                                    } else {
                                        // fallback
                                        jmsg.set(i, val.toString());
                                    }
                                } catch (Exception ex) {
                                    log.debug("Skipping field {} during jPOS conversion: {}", i, ex.getMessage());
                                }
                            }

                            byte[] jposBytes = jmsg.pack();
                            log.debug("jPOS-packed outgoing response length={} bytes. Hex:\n{}", jposBytes.length, bytesToHex(jposBytes));
                            log.debug("Outgoing response (base64): {}", Base64.getEncoder().encodeToString(jposBytes));

                            out.write((jposBytes.length >> 8) & 0xFF);
                            out.write(jposBytes.length & 0xFF);
                            out.write(jposBytes);
                            out.flush();
                        } else {
                            // fallback to solab bytes
                            byte[] toSend = respBytesSolab != null ? respBytesSolab : response.writeData();
                            out.write((toSend.length >> 8) & 0xFF);
                            out.write(toSend.length & 0xFF);
                            out.write(toSend);
                            out.flush();
                        }
                    } catch (Exception sendEx) {
                        log.error("Failed to pack/send response via jPOS; attempting to send solab bytes", sendEx);
                        try {
                            byte[] toSend = respBytesSolab != null ? respBytesSolab : response.writeData();
                            out.write((toSend.length >> 8) & 0xFF);
                            out.write(toSend.length & 0xFF);
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
                    out.write((respBytes.length >> 8) & 0xFF);
                    out.write(respBytes.length & 0xFF);
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
        // set MTI from solab type (format as 4-digit numeric string, e.g. 0800)
        try {
            int typeInt = response.getType();
            String mtiStr = String.format("%04X", typeInt);
            jmsg.setMTI(mtiStr);
        } catch (Throwable ignore) {
        }
        jmsg.setPackager(jposPackager);
        return jmsg;
    }

    /**
     * Ensure fields that the client expects numeric are digits-only and within configured max length.
     * Does not modify field 70.
     */
    private void sanitizeNumericLlFields(IsoMessage msg) {
        if (msg == null) return;

        Map<Integer, Integer> llnumMax = getIntegerIntegerMap();

        for (Map.Entry<Integer, Integer> e : llnumMax.entrySet()) {
            int fld = e.getKey();
            int maxLen = e.getValue();
            try {
                if (fld == 70) continue;            // do not alter network management code
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
        Map<Integer, Integer> llnumMax = new HashMap<>();
        llnumMax.put(2, 19);   // PAN
        llnumMax.put(32, 11);
        llnumMax.put(33, 11);
        llnumMax.put(35, 37);  // track 2 - may include '='; if so remove from map
        llnumMax.put(99, 11);
        llnumMax.put(100, 11);
        llnumMax.put(101, 17);
        llnumMax.put(102, 28);
        llnumMax.put(103, 17);
        llnumMax.put(104, 999);
        return llnumMax;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
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
}