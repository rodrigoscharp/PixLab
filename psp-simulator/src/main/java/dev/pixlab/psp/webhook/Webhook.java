package dev.pixlab.psp.webhook;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class Webhook {

    @Id
    private String chave;

    private String webhookUrl;
    private Instant criacao;

    protected Webhook() {}

    public Webhook(String chave, String webhookUrl, Instant criacao) {
        this.chave = chave;
        this.webhookUrl = webhookUrl;
        this.criacao = criacao;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }
}
