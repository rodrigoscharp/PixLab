package dev.pixlab.psp.webhook;

import dev.pixlab.contracts.pix.PixWebhook;
import dev.pixlab.psp.pix.PixRecebidoEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Entrega o webhook {@code POST {webhookUrl}/pix} depois que o Pix foi gravado no extrato.
 * Sem retentativa nem caos por enquanto: falhas são apenas logadas (retentativa com backoff entra na F3).
 */
@Component
class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookRepository webhooks;
    private final RestClient http;

    WebhookDispatcher(WebhookRepository webhooks, RestClient.Builder http) {
        this.webhooks = webhooks;
        this.http = http.build();
    }

    @Async
    @TransactionalEventListener
    void onPixRecebido(PixRecebidoEvent event) {
        var webhook = webhooks.findById(event.chave());
        if (webhook.isEmpty()) {
            log.info("Sem webhook para a chave {}; e2eId {} fica só no extrato", event.chave(), event.pix().endToEndId());
            return;
        }
        var url = webhook.get().getWebhookUrl() + "/pix";
        try {
            http.post().uri(url).body(new PixWebhook(List.of(event.pix()))).retrieve().toBodilessEntity();
            log.info("Webhook entregue: e2eId {} -> {}", event.pix().endToEndId(), url);
        } catch (RestClientException e) {
            log.warn("Falha ao entregar webhook do e2eId {} para {}: {}", event.pix().endToEndId(), url, e.getMessage());
        }
    }
}
