package com.demo.bankagent.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Test clock with a fixed, adjustable instant so that dates, periods and draft expiry are deterministic. */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock(LocalDateTime start, ZoneId zone) {
        this.zone = zone;
        this.instant = start.atZone(zone).toInstant();
    }

    public void set(LocalDateTime dateTime) {
        this.instant = dateTime.atZone(zone).toInstant();
    }

    public void advance(Duration duration) {
        this.instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
