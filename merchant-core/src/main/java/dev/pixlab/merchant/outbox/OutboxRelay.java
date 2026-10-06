package dev.pixlab.merchant.outbox;

import static java.time.ZoneOffset.UTC;

import dev.pixlab.merchant.support.Poller;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publica a outbox no RabbitMQ e marca como publicada. Entrega at-least-once: se cair entre publicar e marcar,
 * o evento sai de novo, então consumidores precisam ser idempotentes (chave: {@code messageId} = id da outbox).
 */
@Component
@ConditionalOnProperty(name = "pixlab.outbox.relay.enabled", matchIfMissing = true)
class OutboxRelay {

    record Row(long id, String type, String aggregateId, String payload) {}

    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    private final Clock clock;

    OutboxRelay(JdbcClient jdbc, RabbitTemplate rabbit, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.tx = tx;
        this.clock = clock;
    }

    boolean publishBatch() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var rows = jdbc.sql("""
                            select id, type, aggregate_id, payload::text as payload from outbox
                            where published_at is null order by id limit 100
                            for update skip locked""")
                    .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("type"), rs.getString("aggregate_id"),
                            rs.getString("payload")))
                    .list();
            if (rows.isEmpty()) {
                return false;
            }
            rabbit.invoke(ops -> {
                rows.forEach(r -> ops.send(AmqpConfig.EXCHANGE, r.type(), message(r)));
                ops.waitForConfirmsOrDie(Duration.ofSeconds(10).toMillis());
                return null;
            });
            jdbc.sql("update outbox set published_at = :now where id in (:ids)")
                    .param("now", clock.instant().atOffset(UTC))
                    .param("ids", rows.stream().map(Row::id).toList())
                    .update();
            return true;
        }));
    }

    private static Message message(Row row) {
        var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(Long.toString(row.id()));
        props.setType(row.type());
        props.setHeader("aggregateId", row.aggregateId());
        return new Message(row.payload().getBytes(StandardCharsets.UTF_8), props);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "pixlab.outbox.relay.enabled", matchIfMissing = true)
    static class PollerConfig {

        @Bean
        Poller outboxPoller(OutboxRelay relay) {
            return new Poller("outbox", 1, Duration.ofMillis(200), relay::publishBatch);
        }
    }
}
