package dev.pixlab.merchant.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Roda {@code work} em N threads virtuais enquanto houver trabalho; quando {@code work} devolve false,
 * espera {@code idle} antes de tentar de novo.
 */
public class Poller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Poller.class);

    private final String name;
    private final int workers;
    private final Duration idle;
    private final BooleanSupplier work;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public Poller(String name, int workers, Duration idle, BooleanSupplier work) {
        this.name = name;
        this.workers = workers;
        this.idle = idle;
        this.work = work;
    }

    @Override
    public synchronized void start() {
        running = true;
        for (int i = 0; i < workers; i++) {
            threads.add(Thread.ofVirtual().name(name + "-" + i).start(this::loop));
        }
    }

    private void loop() {
        while (running) {
            boolean didWork;
            try {
                didWork = work.getAsBoolean();
            } catch (RuntimeException e) {
                log.error("{}: falha inesperada no ciclo", name, e);
                didWork = false;
            }
            if (!didWork) {
                try {
                    Thread.sleep(idle);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        for (var t : threads) {
            try {
                t.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
