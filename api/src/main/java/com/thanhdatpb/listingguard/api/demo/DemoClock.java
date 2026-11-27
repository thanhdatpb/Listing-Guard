package com.thanhdatpb.listingguard.api.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Supplier;

/** Scopes synthetic dates to the synchronous admin seed thread. Other calls use the real clock. */
public final class DemoClock extends Clock {
    private final ThreadLocal<Instant> seedTime;
    private final Clock delegate;

    public DemoClock() {
        this(Clock.systemUTC(), new ThreadLocal<>());
    }

    private DemoClock(Clock delegate, ThreadLocal<Instant> seedTime) {
        this.delegate = delegate;
        this.seedTime = seedTime;
    }

    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return ZoneOffset.UTC.equals(zone) && ZoneOffset.UTC.equals(getZone())
                ? this : new DemoClock(delegate.withZone(zone), seedTime);
    }

    @Override
    public Instant instant() {
        Instant time = seedTime.get();
        return time == null ? delegate.instant() : time;
    }

    <T> T at(Instant time, Supplier<T> work) {
        if (seedTime.get() != null) {
            throw new IllegalStateException("Nested demo clock");
        }
        seedTime.set(time);
        try {
            return work.get();
        } finally {
            seedTime.remove();
        }
    }
}
