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
    private record Entry(String turn, String receipt, String hospital, String department, Instant expires, BookingTurn input) {}
    private final Map<String, Entry> entries = new HashMap<>();
    private final Clock clock;
    private final Map<String,String> evidenceReplies = new HashMap<>();
    public AppointmentQueryContext() { this(Clock.systemUTC()); }
    AppointmentQueryContext(Clock clock) { this.clock = clock; }
    public synchronized void begin(String conversation) { begin(conversation, null); }
    public synchronized void begin(String conversation, String rawText) {
        entries.entrySet().removeIf(e -> !e.getValue().expires().isAfter(clock.instant()));
        if (entries.size() >= 1024 && !entries.containsKey(conversation)) {
            throw new IllegalStateException("Query context capacity reached");
        }
        evidenceReplies.keySet().retainAll(entries.values().stream().map(Entry::turn).collect(java.util.stream.Collectors.toSet()));
        var previous=entries.get(conversation);
        if(previous!=null)evidenceReplies.remove(previous.turn());
        var input=rawText==null?null:BookingTurn.parse(rawText, previous==null?null:previous.input(),
                java.time.LocalDate.ofInstant(clock.instant(),java.time.ZoneId.of("Asia/Shanghai")));
        entries.put(conversation, new Entry("booking_"+UUID.randomUUID(), null, null, null,
                clock.instant().plus(Duration.ofMinutes(5)), input));
    }
    public synchronized BookingTurn input(String conversation) {
        var e=entries.get(conversation);
        return e!=null&&e.expires().isAfter(clock.instant())?e.input():null;
    }
    public synchronized BookingTurn byMessageName(String name) {
        if(name==null)return null;
        return entries.values().stream().filter(e->name.equals(e.turn())&&e.expires().isAfter(clock.instant()))
                .map(Entry::input).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }
    public synchronized void evidenceReply(String conversation,String turn,String reply) {
        if(turn!=null&&turn.equals(turn(conversation))&&reply!=null&&!reply.isBlank())evidenceReplies.put(turn,reply);
    }
    public synchronized String evidenceReplyFor(String name) {
        return byMessageName(name)==null?null:evidenceReplies.get(name);
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
                clock.instant().plusSeconds(90),e.input()));
        return receipt;
    }
    public synchronized void failed(String conversation, String turn) {
        var e=entries.get(conversation);
        if(e!=null && e.turn().equals(turn)) entries.put(conversation,
                new Entry(e.turn(),null,null,null,e.expires(),e.input()));
    }
    public synchronized boolean consume(String conversation, String receipt, String hospital, String department) {
        var e=entries.get(conversation);
        if(e==null || receipt==null || !receipt.equals(e.receipt())
                || !e.expires().isAfter(clock.instant()) || !hospital.strip().equals(e.hospital())
                || !department.strip().equals(e.department())) return false;
        entries.put(conversation,new Entry(e.turn(),null,null,null,e.expires(),e.input()));
        return true;
    }
}
