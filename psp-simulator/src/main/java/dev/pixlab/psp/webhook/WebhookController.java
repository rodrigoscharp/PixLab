package dev.pixlab.psp.webhook;

import dev.pixlab.contracts.pix.WebhookRequest;
import java.net.URI;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class WebhookController {

    private final WebhookRepository webhooks;
    private final Clock clock;

    WebhookController(WebhookRepository webhooks, Clock clock) {
        this.webhooks = webhooks;
        this.clock = clock;
    }

    @PutMapping("/webhook/{chave}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void configurar(@PathVariable String chave, @RequestBody WebhookRequest request) {
        if (request.webhookUrl() == null || !URI.create(request.webhookUrl()).isAbsolute()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "webhookUrl deve ser uma URL absoluta");
        }
        webhooks.save(new Webhook(chave, request.webhookUrl(), clock.instant()));
    }
}
