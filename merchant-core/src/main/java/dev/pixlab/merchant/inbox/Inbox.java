package dev.pixlab.merchant.inbox;

import static java.time.ZoneOffset.UTC;

import dev.pixlab.merchant.support.Traces;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acesso à {@code webhook_inbox}. Idempotência vem da constraint UNIQUE, não de um SELECT prévio. */
@Repository
public class Inbox {

    private final JdbcClient jdbc;
    private final Traces traces;
    private final MeterRegistry meters;

    Inbox(JdbcClient jdbc, Traces traces, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.traces = traces;
        this.meters = meters;
    }

    /**
     * Grava o evento se a chave for nova. Conta {@code webhook_received_total} ou
     * {@code webhook_duplicate_discarded_total} por source.
     *
     * @return true se o evento é novo; false se já estava na inbox (duplicata descartada)
     */
    public boolean offer(String source, String eventKey, String payloadJson) {
        var fresh = jdbc.sql("""
                        insert into webhook_inbox (source, event_key, payload, trace_parent)
                        values (:source, :key, cast(:payload as jsonb), :trace)
                        on conflict (source, event_key) do nothing""")
                .param("source", source)
                .param("key", eventKey)
                .param("payload", payloadJson)
                .param("trace", traces.capture())
                .update() == 1;
        meters.counter(fresh ? "webhook.received" : "webhook.duplicate.discarded", "source", source).increment();
        return fresh;
    }

    Optional<InboxEvent> claimNext(Instant now) {
        return jdbc.sql("""
                        select id, source, event_key, payload::text as payload, attempts, trace_parent
                        from webhook_inbox
                        where status = 'PENDING' and next_attempt_at <= :now
                        order by next_attempt_at, id
                        limit 1
                        for update skip locked""")
                .param("now", now.atOffset(UTC))
                .query((rs, n) -> new InboxEvent(rs.getLong("id"), rs.getString("source"), rs.getString("event_key"),
                        rs.getString("payload"), rs.getInt("attempts"), rs.getString("trace_parent")))
                .optional();
    }

    void markProcessed(long id, Instant now) {
        jdbc.sql("update webhook_inbox set status = 'PROCESSED', processed_at = :now, attempts = attempts + 1 where id = :id")
                .param("now", now.atOffset(UTC))
                .param("id", id)
                .update();
    }

    void markRetry(long id, Instant nextAttemptAt, String error) {
        jdbc.sql("""
                        update webhook_inbox set attempts = attempts + 1, next_attempt_at = :next, last_error = :error
                        where id = :id and status = 'PENDING'""")
                .param("next", nextAttemptAt.atOffset(UTC))
                .param("error", error)
                .param("id", id)
                .update();
    }

    void markQuarantined(long id, Instant now, String error) {
        jdbc.sql("""
                        update webhook_inbox set status = 'QUARANTINED', processed_at = :now, attempts = attempts + 1,
                            last_error = :error
                        where id = :id and status = 'PENDING'""")
                .param("now", now.atOffset(UTC))
                .param("error", error)
                .param("id", id)
                .update();
    }

    public long countByStatus(InboxStatus status) {
        return jdbc.sql("select count(*) from webhook_inbox where status = :status")
                .param("status", status.name())
                .query(Long.class)
                .single();
    }
}
