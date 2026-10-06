package dev.pixlab.merchant.support;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Métricas lidas do banco no momento do scrape (design doc, seção 11). {@code ledger_imbalance} deve ser sempre 0;
 * qualquer outro valor é bug.
 */
@Component
class MerchantMetrics {

    MerchantMetrics(MeterRegistry meters, JdbcClient jdbc) {
        Gauge.builder("inbox.lag", jdbc, j -> j.sql("""
                        select coalesce(extract(epoch from now() - min(received_at)), 0) from webhook_inbox
                        where status = 'PENDING'""").query(Double.class).single())
                .description("Idade do evento pendente mais antigo da inbox").baseUnit("seconds").register(meters);
        for (var status : new String[] {"PENDING", "PROCESSED", "QUARANTINED"}) {
            Gauge.builder("inbox.events", jdbc, j -> j.sql("select count(*) from webhook_inbox where status = ?")
                            .param(status).query(Long.class).single())
                    .description("Eventos na inbox por estado").tag("status", status).register(meters);
        }
        Gauge.builder("ledger.imbalance", jdbc, j -> j.sql("select coalesce(sum(debit) - sum(credit), 0) from ledger_entry")
                        .query(Double.class).single())
                .description("Σ débitos − Σ créditos do ledger inteiro; sempre 0").register(meters);
        Gauge.builder("ledger.unbalanced.transactions", jdbc, j -> j.sql("""
                        select count(*) from (select tx_id from ledger_entry group by tx_id
                                              having sum(debit) <> sum(credit)) t""").query(Long.class).single())
                .description("Transações do ledger com débito ≠ crédito; sempre 0").register(meters);
        Gauge.builder("outbox.unpublished", jdbc, j -> j.sql("select count(*) from outbox where published_at is null")
                        .query(Long.class).single())
                .description("Eventos de domínio ainda não publicados no RabbitMQ").register(meters);
    }
}
