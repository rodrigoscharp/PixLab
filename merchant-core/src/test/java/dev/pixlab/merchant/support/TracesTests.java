package dev.pixlab.merchant.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pixlab.merchant.TestcontainersConfiguration;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** O trace atravessa a fila: o que foi capturado ao enfileirar continua no processamento, em outra thread. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureTracing
class TracesTests {

    @Autowired
    Traces traces;

    @Autowired
    Tracer tracer;

    @Test
    void processamentoContinuaOTraceDeQuemEnfileirou() throws Exception {
        var span = tracer.nextSpan().name("webhook").start();
        String traceParent;
        try (var scope = tracer.withSpan(span)) {
            traceParent = traces.capture();
        } finally {
            span.end();
        }
        assertThat(traceParent).startsWith("00-" + span.context().traceId());

        var seen = new String[2];
        var worker = Thread.ofVirtual().start(() -> traces.inSpan("inbox.process", traceParent, () -> {
            seen[0] = tracer.currentSpan().context().traceId();
            seen[1] = tracer.currentSpan().context().parentId();
            return null;
        }));
        worker.join();

        assertThat(seen[0]).isEqualTo(span.context().traceId());
        assertThat(seen[1]).isEqualTo(span.context().spanId());
    }

    @Test
    void foraDeTraceNaoCapturaNada() {
        assertThat(traces.capture()).isNull();
    }
}
