package dev.pixlab.merchant.psp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/** Registra no PSP a URL de webhook da chave do recebedor ao subir. */
@Component
class WebhookRegistration {

    private static final Logger log = LoggerFactory.getLogger(WebhookRegistration.class);

    private final PspClient psp;
    private final PspProperties props;

    WebhookRegistration(PspClient psp, PspProperties props) {
        this.psp = psp;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    void register() {
        try {
            psp.configurarWebhook(props.chave(), props.webhookUrl().toString());
            log.info("Webhook registrado no PSP: {} -> {}", props.chave(), props.webhookUrl());
        } catch (RestClientException e) {
            log.warn("Não foi possível registrar o webhook no PSP ({}): {}", props.baseUrl(), e.getMessage());
        }
    }
}
