package dev.pixlab.merchant.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Grava eventos de domínio na mesma transação do lançamento (ADR-0003). */
@Repository
public class Outbox {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    Outbox(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String aggregate, String aggregateId, String type, Object payload) {
        jdbc.sql("""
                        insert into outbox (aggregate, aggregate_id, type, payload)
                        values (:aggregate, :id, :type, cast(:payload as jsonb))""")
                .param("aggregate", aggregate)
                .param("id", aggregateId)
                .param("type", type)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }
}
