package dev.pixlab.merchant.payment;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.contracts.pix.PixWebhook;
import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.merchant.inbox.Inbox;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recebe {@code POST {webhookUrl}/pix}. Faz o mínimo: valida, grava na inbox e responde 200 (ADR-0002).
 * Duplicatas são descartadas pela constraint UNIQUE da inbox.
 */
@RestController
class PixWebhookController {

    private final Inbox inbox;
    private final JsonMapper json;

    PixWebhookController(Inbox inbox, JsonMapper json) {
        this.inbox = inbox;
        this.json = json;
    }

    @PostMapping("/webhook/pix")
    @Transactional
    void receive(@RequestBody PixWebhook webhook) {
        if (webhook.pix() == null || webhook.pix().isEmpty()) {
            throw badRequest("lote de pix vazio");
        }
        webhook.pix().forEach(PixWebhookController::validate);
        webhook.pix().forEach(pix -> inbox.offer(PixCreditHandler.SOURCE, pix.endToEndId(), json.writeValueAsString(pix)));
    }

    private static void validate(Pix pix) {
        if (pix.endToEndId() == null || !pix.endToEndId().matches("E\\d{8}\\d{12}[a-zA-Z0-9]{11}")) {
            throw badRequest("endToEndId inválido: " + pix.endToEndId());
        }
        try {
            Valor.parse(pix.valor());
            Instant.parse(pix.horario());
        } catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            throw badRequest("pix " + pix.endToEndId() + " inválido: " + e.getMessage());
        }
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
