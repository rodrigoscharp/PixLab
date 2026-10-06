package dev.pixlab.merchant.psp;

import dev.pixlab.contracts.pix.CobRequest;
import dev.pixlab.contracts.pix.CobResponse;
import dev.pixlab.contracts.pix.Devolucao;
import dev.pixlab.contracts.pix.DevolucaoRequest;
import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.contracts.pix.PixListResponse;
import dev.pixlab.contracts.pix.WebhookRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Cliente da API Pix do PSP. */
@Component
public class PspClient {

    private static final int PAGE_SIZE = 500;

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

    public Devolucao solicitarDevolucao(String e2eId, String id, DevolucaoRequest request) {
        return http.put().uri("/pix/{e2eId}/devolucao/{id}", e2eId, id).body(request).retrieve().body(Devolucao.class);
    }

    public PixListResponse listarPixPagina(Instant inicio, Instant fim, int page, int size) {
        return http.get()
                .uri(b -> b.path("/pix").queryParam("inicio", inicio).queryParam("fim", fim)
                        .queryParam("paginacao.paginaAtual", page).queryParam("paginacao.itensPorPagina", size).build())
                .retrieve().body(PixListResponse.class);
    }

    /** Todos os Pix recebidos em [inicio, fim), percorrendo as páginas. */
    public List<Pix> listarPix(Instant inicio, Instant fim) {
        var all = new ArrayList<Pix>();
        int page = 0;
        PixListResponse response;
        do {
            response = listarPixPagina(inicio, fim, page, PAGE_SIZE);
            all.addAll(response.pix());
            page++;
        } while (page < response.parametros().paginacao().quantidadeDePaginas());
        return all;
    }
}
