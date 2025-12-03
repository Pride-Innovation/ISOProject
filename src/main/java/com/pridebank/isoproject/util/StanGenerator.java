package com.pridebank.isoproject.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class StanGenerator {

    private final AtomicInteger globalCounter = new AtomicInteger(1);
    private final ConcurrentHashMap<String, AtomicInteger> terminalCounters = new ConcurrentHashMap<>();

    @Autowired
    private Clock clock;

    private volatile LocalDate lastResetDate;

    public StanGenerator() {
        // lastResetDate will be set on first use via clock to avoid NPE before injection
        this.lastResetDate = LocalDate.now();
    }

    private static final int MAX_STAN = 999999;
    private static final int MIN_STAN = 1;

    public synchronized String generateStan() {
        checkDailyReset();
        int stan = globalCounter.getAndIncrement();
        if (stan > MAX_STAN) {
            globalCounter.set(MIN_STAN);
            stan = MIN_STAN;
        }
        return String.format("%06d", stan);
    }

    public synchronized String generateStanForTerminal(String terminalId) {
        checkDailyReset();
        AtomicInteger counter = terminalCounters.computeIfAbsent(
                terminalId, k -> new AtomicInteger(MIN_STAN));
        int stan = counter.getAndIncrement();
        if (stan > MAX_STAN) {
            counter.set(MIN_STAN);
            stan = MIN_STAN;
        }
        return String.format("%06d", stan);
    }

    private void checkDailyReset() {
        LocalDate today = (clock != null) ? LocalDate.now(clock) : LocalDate.now();
        if (lastResetDate == null) {
            lastResetDate = today;
        }
        if (!today.equals(lastResetDate)) {
            resetAllCounters();
            lastResetDate = today;
        }
    }

    public synchronized void resetAllCounters() {
        globalCounter.set(MIN_STAN);
        terminalCounters.clear();
        log.info("All STAN counters reset");
    }
}