package dev.pixlab.merchant.payment;

import dev.pixlab.contracts.pix.PixWebhook;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Recebe {@code POST {webhookUrl}/pix} do PSP. */
@RestController
class PixWebhookController {

    private final PixReceiver receiver;

    PixWebhookController(PixReceiver receiver) {
        this.receiver = receiver;
    }

    @PostMapping("/webhook/pix")
    void receive(@RequestBody PixWebhook webhook) {
        webhook.pix().forEach(receiver::receive);
    }
}
