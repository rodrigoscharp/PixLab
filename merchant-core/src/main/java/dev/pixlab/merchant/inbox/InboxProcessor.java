package dev.pixlab.merchant.inbox;

import dev.pixlab.merchant.support.Poller;
import dev.pixlab.merchant.support.Traces;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consome a inbox: reivindica um evento com {@code FOR UPDATE SKIP LOCKED}, aplica e marca o resultado
 * na mesma transação. Se o handler lançar exceção, a transação inteira volta e a falha é registrada à parte.
 */
@Component
public class InboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboxProcessor.class);

    private final Inbox inbox;
    private final Map<String, InboxEventHandler> handlers;
    private final TransactionTemplate tx;
    private final InboxProperties props;
    private final Clock clock;
    private final Traces traces;

    InboxProcessor(Inbox inbox, List<InboxEventHandler> handlers, TransactionTemplate tx, InboxProperties props,
            Clock clock, Traces traces) {
        this.traces = traces;
        this.inbox = inbox;
        this.handlers = handlers.stream().collect(Collectors.toMap(InboxEventHandler::source, Function.identity()));
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    /** @return true se havia um evento para processar */
    public boolean processNext() {
        var claimed = new AtomicReference<InboxEvent>();
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                var event = inbox.claimNext(clock.instant()).orElse(null);
                if (event == null) {
                    return false;
                }
                claimed.set(event);
                var handler = handlerFor(event);
                record(event, traces.inSpan("inbox.process " + event.source(), event.traceParent(),
                        () -> handler.handle(event)));
                return true;
            }));
        } catch (RuntimeException e) {
            var event = claimed.get();
            if (event == null) {
                throw e;
            }
            log.warn("Falha ao processar evento {} {}: {}", event.source(), event.eventKey(), e.toString());
            tx.executeWithoutResult(status -> record(event, new Outcome.Retry(e.toString())));
            return true;
        }
    }

    /** Processa até esvaziar o que estiver vencido. Útil em testes e no reprocessamento manual. */
    public int drain() {
        int count = 0;
        while (processNext()) {
            count++;
        }
        return count;
    }

    private InboxEventHandler handlerFor(InboxEvent event) {
        var handler = handlers.get(event.source());
        if (handler == null) {
            throw new IllegalStateException("sem handler para a source " + event.source());
        }
        return handler;
    }

    private void record(InboxEvent event, Outcome outcome) {
        var now = clock.instant();
        switch (outcome) {
            case Outcome.Applied a -> inbox.markProcessed(event.id(), now);
            case Outcome.Quarantine(var reason) -> {
                log.warn("Evento {} {} em quarentena: {}", event.source(), event.eventKey(), reason);
                inbox.markQuarantined(event.id(), now, reason);
            }
            case Outcome.Retry(var reason) -> {
                if (event.attempts() + 1 >= props.maxAttempts()) {
                    log.warn("Evento {} {} esgotou {} tentativas: {}", event.source(), event.eventKey(),
                            props.maxAttempts(), reason);
                    inbox.markQuarantined(event.id(), now, "tentativas esgotadas: " + reason);
                } else {
                    inbox.markRetry(event.id(), now.plus(props.backoff(event.attempts())), reason);
                }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PollerConfig {

        @Bean
        Poller inboxPoller(InboxProcessor processor, InboxProperties props) {
            return new Poller("inbox", props.workers(), props.idle(), processor::processNext);
        }
    }
}
