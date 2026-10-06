package dev.pixlab.psp.webhook;

import static java.time.ZoneOffset.UTC;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.contracts.pix.PixWebhook;
import dev.pixlab.psp.chaos.ChaosEngine;
import dev.pixlab.psp.chaos.DeliveryPlan;
import dev.pixlab.psp.support.VirtualClock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Agenda as entregas de webhook de um Pix na mesma transação que o grava no extrato. */
@Component
public class WebhookScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookScheduler.class);

    private final WebhookRepository webhooks;
    private final ChaosEngine chaos;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final VirtualClock clock;

    WebhookScheduler(WebhookRepository webhooks, ChaosEngine chaos, JdbcClient jdbc, JsonMapper json,
            VirtualClock dispatchClock) {
        this.webhooks = webhooks;
        this.chaos = chaos;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = dispatchClock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(String chave, Pix pix) {
        schedule(chave, pix, chaos.plan(pix));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(String chave, Pix pix, List<DeliveryPlan> plans) {
        var webhook = webhooks.findById(chave);
        if (webhook.isEmpty()) {
            log.info("Sem webhook para a chave {}; e2eId {} fica só no extrato", chave, pix.endToEndId());
            return;
        }
        var url = webhook.get().getWebhookUrl() + "/pix";
        var now = clock.instant();
        for (var plan : plans) {
            jdbc.sql("""
                            insert into webhook_delivery (e2e_id, url, payload, forged, fake_failures, next_attempt_at)
                            values (:e2e, :url, :payload, :forged, :fake, :at)""")
                    .param("e2e", pix.endToEndId())
                    .param("url", url)
                    .param("payload", json.writeValueAsString(new PixWebhook(List.of(plan.payload()))))
                    .param("forged", plan.forged())
                    .param("fake", plan.fakeFailures())
                    .param("at", now.plus(plan.delay()).atOffset(UTC))
                    .update();
        }
    }
}
