package dev.pixlab.psp.support;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Leva o contexto de trace por onde o HTTP não leva: linhas de banco (entrega de webhook, inbox) lidas depois
 * por outra thread. Grava o {@code traceparent} (W3C) ao enfileirar e o retoma ao processar.
 */
@Component
public class Traces {

    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    Traces(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** {@code traceparent} do span atual, ou null fora de um trace. */
    public String capture() {
        var context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        var carrier = new HashMap<String, String>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /** Roda {@code work} num span filho de {@code traceParent} (ou do span atual, se null). */
    public <T> T inSpan(String name, String traceParent, Supplier<T> work) {
        var span = traceParent == null
                ? tracer.nextSpan().name(name)
                : propagator.extract(Map.of(TRACEPARENT, traceParent), Map::get).name(name).start();
        if (traceParent == null) {
            span.start();
        }
        try (var scope = tracer.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
