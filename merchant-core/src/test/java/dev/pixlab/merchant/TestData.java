package dev.pixlab.merchant;

import dev.pixlab.contracts.pix.WebhookSignature;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Geradores de dados de teste compartilhados entre pacotes. */
public final class TestData {

    private TestData() {}

    public static String e2eId() {
        return "E12345678202610061530" + random(11);
    }

    public static String txid() {
        return random(32);
    }

    public static String random(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }

    public static String webhookBody(String e2eId, String txid, String valor) {
        return """
                {"pix":[{"endToEndId":"%s","txid":"%s","valor":"%s","horario":"2026-10-06T15:30:12.358Z",
                 "infoPagador":"teste","devolucoes":[]}]}""".formatted(e2eId, txid, valor);
    }

    public static String sign(String body) {
        return WebhookSignature.sign("pixlab-dev-secret", body.getBytes(StandardCharsets.UTF_8));
    }
}
