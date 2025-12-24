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

                    // NOTE: Do NOT prune any fields; preserve exactly what processor set.

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
                                                if (inner instanceof byte[]) {
                                                    jmsg.set(127, (byte[]) inner);
                                                    continue;
                                                }
                                            }
                                        } catch (Exception ignore) {
                                        }

                                        if (val instanceof byte[]) {
                                            jmsg.set(127, (byte[]) val);
                                            continue;
                                        }

                                        if (val instanceof String) {
                                            String s = ((String) val).trim();
                                            if (s.matches("(?i)^[0-9A-F]+$") && (s.length() % 2 == 0)) {
                                                byte[] b = hexToBytes(s);
                                                jmsg.set(127, b);
                                                continue;
                                            }
                                            jmsg.set(127, s);
                                            continue;
                                        }

                                        jmsg.set(127, val.toString());
                                        continue;
                                    }
                                    // --- end 127 handling ---

                                    // Fields 7/12/13: provide numeric/formatted values to jPOS
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

                                    // When target packager expects binary, convert hex string -> bytes if appropriate
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
        llnumMax.put(2, 19);
        llnumMax.put(32, 11);
        llnumMax.put(33, 11);
        llnumMax.put(35, 37);
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