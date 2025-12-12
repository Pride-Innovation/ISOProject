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
//import java.util.Optional;
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

    private GenericPackager jposPackager;

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private ExecutorService acceptLoop;

    @PostConstruct
    public void start() throws Exception {
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
                    log.info("Request details Field 123 ::: {}", request.getField(123));
                    log.info("Request details two::: {}", request.getField(127));
                    log.info("Request details three 102 ::: {}", request.getField(102));

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

                    // Preserve important high fields (102, 123, 127). remove other high fields if present.
                    try {
                        for (int f = 99; f <= 128; f++) {
                            if (f == 102 || f == 123 || f == 127) continue; // preserve these
                            if (response.hasField(f)) {
                                try {
                                    response.removeFields(f);
                                } catch (NoSuchMethodError nsme) {
                                    try {
                                        response.removeFields(f);
                                    } catch (Exception ignored) {
                                    }
                                } catch (Exception ignore) {
                                }
                                log.debug("Removed field {} from outgoing response to avoid client unpack issues", f);
                            }
                        }
                    } catch (Exception ex) {
                        log.debug("Failed removing high-numbered fields before send: {}", ex.getMessage());
                    }

                    try {
                        if (jposPackager != null) {
                            ISOMsg jmsg = getIsoMsg(response);

                            for (int i = 2; i <= 128; i++) {
                                try {
                                    if (!response.hasField(i)) continue;
                                    Object val = response.getObjectValue(i);
                                    if (val == null) continue;

                                    // --- special handling for parent field 127 (composite) ---
                                    if (i == 127) {
                                        // 1) if solab stored nested IsoMessage for 127, use its raw bytes
                                        try {
                                            IsoValue<?> v127 = response.getField(127);
                                            if (v127 != null) {
                                                Object inner = v127.getValue();
                                                if (inner instanceof IsoMessage) {
                                                    try {
                                                        byte[] nested = ((IsoMessage) inner).writeData();
                                                        if (nested != null && nested.length > 0) {
                                                            jmsg.set(127, nested);
                                                            continue;
                                                        }
                                                    } catch (Exception ignore) {
                                                    }
                                                }
                                                // if inner is byte[] already, pass-through
                                                if (inner instanceof byte[]) {
                                                    jmsg.set(127, (byte[]) inner);
                                                    continue;
                                                }
                                            }
                                        } catch (Exception ignore) {
                                        }

                                        // 2) if value itself is byte[], pass-through
                                        if (val instanceof byte[]) {
                                            jmsg.set(127, (byte[]) val);
                                            continue;
                                        }

                                        // 3) if value looks like hex string, convert -> bytes
                                        if (val instanceof String) {
                                            String s = ((String) val).trim();
                                            if (s.matches("(?i)^[0-9A-F]+$") && (s.length() % 2 == 0)) {
                                                byte[] b = hexToBytes(s);
                                                jmsg.set(127, b);
                                                continue;
                                            }
                                            // 4) generic fallback: set textual representation (less desirable)
                                            jmsg.set(127, s);
                                            continue;
                                        }

                                        // fallback generic
                                        jmsg.set(127, val.toString());
                                        continue;
                                    }
                                    // --- end 127 handling ---

                                    // Dates -> formatted strings for fields 7/12/13
                                    if (val instanceof Date) {
                                        String s;
                                        if (i == 7) s = F7.format((Date) val);
                                        else if (i == 12) s = F12.format((Date) val);
                                        else if (i == 13) s = F13.format((Date) val);
                                        else s = val.toString();
                                        jmsg.set(i, s);
                                        continue;
                                    }

                                    // When target packager expects binary, convert hex string -> bytes if appropriate
                                    if (val instanceof String) {
                                        try {
                                            String cls = jposPackager.getFieldPackager(i).getClass().getSimpleName();
                                            if (cls.toUpperCase().contains("BINARY") || cls.toUpperCase().contains("IFB_BINARY") || cls.toUpperCase().contains("IFA_BINARY")) {
                                                String s = (String) val;
                                                if (s.matches("(?i)^[0-9A-F]+$") && (s.length() % 2 == 0)) {
//                                                    int len = s.length() / 2;
                                                    byte[] b = hexToBytes(s);
                                                    jmsg.set(i, b);
                                                    continue;
                                                }
                                            }
                                        } catch (Exception ignore) {
                                        }
                                    }

                                    // number / non-byte default: stringify
                                    if (val instanceof byte[]) {
                                        jmsg.set(i, (byte[]) val);
                                    } else {
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
                            byte[] toSend = response.writeData();
                            out.write((toSend.length >> 8) & 0xFF);
                            out.write(toSend.length & 0xFF);
                            out.write(toSend);
                            out.flush();
                        }
                    } catch (Exception sendEx) {
                        log.error("Failed to pack/send response via jPOS; attempting to send solab bytes", sendEx);
                        try {
                            byte[] toSend = response.writeData();
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
        // set MTI from solab type (format as 4-digit numeric string, e.g. 0200)
        try {
            int typeInt = response.getType();
            // solab type is numeric (e.g. 0x200) - convert to decimal MTI string
//            String mtiStr = String.format("%04d", typeInt);
            // solab type is numeric (e.g. 0x200) - convert to 4-digit HEX MTI string ("0200", "0210", "0800", "0810")
            jmsg.setMTI(String.format("%04X", typeInt));
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

}