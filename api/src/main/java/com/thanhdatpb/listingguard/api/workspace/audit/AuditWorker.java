package com.thanhdatpb.listingguard.api.workspace.audit;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The threads of the background audit (ADR-061): {@code concurrency} workers that take runs from the queue, and one thread that looks for a
 * change of context and for the periodic audit. They start with the application unless {@code auto-start} is off, and stop with it; a run
 * that was being worked is left with its lease and taken again, by this process after it restarts or by another one.
 */
@Component
@AuditEnabled
public class AuditWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AuditWorker.class);

    private final AuditRunner runner;
    private final AuditTriggers triggers;
    private final AuditProperties properties;
    private volatile ExecutorService executor;
    private volatile boolean running;

    public AuditWorker(AuditRunner runner, AuditTriggers triggers, AuditProperties properties) {
        this.runner = runner;
        this.triggers = triggers;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        if (!properties.autoStart()) {
            return;
        }
        AtomicInteger number = new AtomicInteger();
        executor = Executors.newFixedThreadPool(properties.concurrency() + 1, task -> {
            Thread thread = new Thread(task, "workspace-audit-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < properties.concurrency(); i++) {
            executor.execute(this::work);
        }
        executor.execute(this::watch);
        log.info("Workspace audit started: {} workers, poll {}, drift check {}", properties.concurrency(), properties.pollInterval(),
                properties.driftInterval());
    }

    private void work() {
        while (running) {
            try {
                if (!runner.runNext()) {
                    Thread.sleep(properties.pollInterval().toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Workspace audit worker: {}", e.getClass().getSimpleName());
                sleepQuietly();
            }
        }
    }

    private void watch() {
        while (running) {
            try {
                triggers.checkDrift();
                triggers.checkSchedule();
                Thread.sleep(properties.driftInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Workspace audit trigger check: {}", e.getClass().getSimpleName());
                sleepQuietly();
            }
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(properties.pollInterval().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Works the queue until it is empty, in the caller's thread; returns how many runs it worked. For a test and for a one-off. */
    public int drain() {
        int worked = 0;
        while (runner.runNext()) {
            worked++;
        }
        return worked;
    }

    @Override
    public void stop() {
        running = false;
        ExecutorService pool = executor;
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
