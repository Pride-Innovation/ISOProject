package com.pridebank.isoproject.server;

import com.pridebank.isoproject.service.AtmTransactionProcessor;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.MessageFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private ExecutorService acceptLoop;

    @PostConstruct
    public void start() throws Exception {
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
                    log.info("Transaction Response ::: {}", response);
                    byte[] respBytes = response.writeData();
                    out.write((respBytes.length >> 8) & 0xFF);
                    out.write(respBytes.length & 0xFF);
                    out.write(respBytes);
                    out.flush();
                } catch (java.text.ParseException pe) {
                    IsoMessage errorResp = messageFactory.newMessage(0x210);
                    errorResp.setValue(39, "30", com.solab.iso8583.IsoType.ALPHA, 2);
                    byte[] respBytes = errorResp.writeData();
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