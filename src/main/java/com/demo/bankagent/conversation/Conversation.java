package com.demo.bankagent.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory conversation bound to exactly one (simulated) customer. The transcript keeps the
 * user-visible exchange so the Supervisor can resolve follow-up references.
 */
public class Conversation {

    public enum Role { USER, ASSISTANT, SYSTEM_NOTE }

    public record Turn(Role role, String text, Instant at) {
    }

    private final String id;
    private final String customerId;
    private final Instant createdAt;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Turn> transcript = new ArrayList<>();
    private volatile Instant lastActiveAt;

    Conversation(String id, String customerId, Instant now) {
        this.id = id;
        this.customerId = customerId;
        this.createdAt = now;
        this.lastActiveAt = now;
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public ReentrantLock lock() {
        return lock;
    }

    void touch(Instant now) {
        this.lastActiveAt = now;
    }

    synchronized void append(Role role, String text, Instant now, int maxTurns) {
        transcript.add(new Turn(role, text, now));
        while (transcript.size() > maxTurns) {
            transcript.remove(0);
        }
    }

    public synchronized List<Turn> recentTurns(int max) {
        int from = Math.max(0, transcript.size() - max);
        return List.copyOf(transcript.subList(from, transcript.size()));
    }
}
