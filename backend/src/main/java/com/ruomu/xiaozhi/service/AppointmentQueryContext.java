package com.ruomu.xiaozhi.service;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Short-lived, single-use query receipts. Never an authorization to confirm appointments. */
@Component
public class AppointmentQueryContext {
    private record Entry(String turn, String receipt, String hospital, String department, Instant expires) {}
    private final Map<String, Entry> entries = new HashMap<>();
    private final Clock clock;
    public AppointmentQueryContext() { this(Clock.systemUTC()); }
    AppointmentQueryContext(Clock clock) { this.clock = clock; }
    public synchronized void begin(String conversation) {
        entries.entrySet().removeIf(e -> !e.getValue().expires().isAfter(clock.instant()));
        if (entries.size() >= 1024 && !entries.containsKey(conversation)) {
            throw new IllegalStateException("Query context capacity reached");
        }
        entries.put(conversation, new Entry(UUID.randomUUID().toString(), null, null, null,
                clock.instant().plus(Duration.ofMinutes(5))));
    }
    public synchronized String turn(String conversation) {
        var e=entries.get(conversation);
        return e!=null && e.expires().isAfter(clock.instant()) ? e.turn() : null;
    }
    public synchronized String issued(String conversation, String turn, String hospital, String department) {
        var e=entries.get(conversation);
        if(e==null || turn==null || !turn.equals(e.turn()) || !e.expires().isAfter(clock.instant())) return null;
        String receipt=UUID.randomUUID().toString();
        entries.put(conversation,new Entry(turn,receipt,hospital.strip(),department.strip(),
                clock.instant().plusSeconds(90)));
        return receipt;
    }
    public synchronized void failed(String conversation, String turn) {
        var e=entries.get(conversation);
        if(e!=null && e.turn().equals(turn)) entries.put(conversation,
                new Entry(e.turn(),null,null,null,e.expires()));
    }
    public synchronized boolean consume(String conversation, String receipt, String hospital, String department) {
        var e=entries.get(conversation);
        if(e==null || receipt==null || !receipt.equals(e.receipt())
                || !e.expires().isAfter(clock.instant()) || !hospital.strip().equals(e.hospital())
                || !department.strip().equals(e.department())) return false;
        entries.put(conversation,new Entry(e.turn(),null,null,null,e.expires()));
        return true;
    }
}
