package dev.pixlab.psp.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Relógio do dispatcher: tempo real mais um deslocamento que testes podem avançar. Assim um atraso de
 * "2 horas" (PIX-DELAY) dispara em milissegundos sem esperar (ADR-0004).
 */
public final class VirtualClock extends Clock {

    private final Clock base;
    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public VirtualClock(Clock base) {
        this.base = base;
    }

    public Duration advance(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("o relógio só anda para frente");
        }
        return offset.updateAndGet(o -> o.plus(amount));
    }

    public Duration offset() {
        return offset.get();
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }
}
