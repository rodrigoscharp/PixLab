package dev.pixlab.merchant.psp;

import dev.pixlab.contracts.pix.CobRequest;
import dev.pixlab.contracts.pix.CobResponse;
import dev.pixlab.contracts.pix.WebhookRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Cliente da API Pix do PSP. */
@Component
public class PspClient {

    private final RestClient http;

    PspClient(RestClient.Builder http, PspProperties props) {
        this.http = http.baseUrl(props.baseUrl().toString()).build();
    }

    public CobResponse criarCob(String txid, CobRequest request) {
        return http.put().uri("/cob/{txid}", txid).body(request).retrieve().body(CobResponse.class);
    }

    public void configurarWebhook(String chave, String webhookUrl) {
        http.put().uri("/webhook/{chave}", chave).body(new WebhookRequest(webhookUrl)).retrieve().toBodilessEntity();
    }
}
