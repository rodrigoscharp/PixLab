package dev.pixlab.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pixlab.contracts.pix.CobResponse;
import dev.pixlab.contracts.pix.CobStatus;
import dev.pixlab.contracts.pix.Pix;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * F1 — Pix caminho feliz, de ponta a ponta: recebedor cria a cobrança no PSP, o pagador simulado paga,
 * o PSP entrega o webhook, e o recebedor conclui a cobrança com o ledger balanceado.
 */
class PixHappyPathE2ETest {

    record ChargeResponse(String txid, String valor, String status, List<String> endToEndIds) {}

    record BalancesResponse(Map<String, String> accounts, String total) {}

    static final PostgreSQLContainer pspDb = new PostgreSQLContainer("postgres:16-alpine");
    static final PostgreSQLContainer merchantDb = new PostgreSQLContainer("postgres:16-alpine");

    static ServiceProcess psp;
    static ServiceProcess merchant;
    static RestClient pspApi;
    static RestClient merchantApi;

    @BeforeAll
    static void start() throws Exception {
        pspDb.start();
        merchantDb.start();

        psp = ServiceProcess.start("psp-simulator", "pixlab.e2e.psp-jar", datasource(pspDb));

        var merchantPort = ServiceProcess.freePort();
        var merchantArgs = new ArrayList<>(datasource(merchantDb));
        merchantArgs.add("--pixlab.psp.base-url=" + psp.baseUrl());
        merchantArgs.add("--pixlab.psp.webhook-url=http://localhost:" + merchantPort + "/webhook");
        merchant = ServiceProcess.start("merchant-core", "pixlab.e2e.merchant-jar", merchantArgs, merchantPort);

        pspApi = RestClient.create(psp.baseUrl().toString());
        merchantApi = RestClient.create(merchant.baseUrl().toString());
    }

    @AfterAll
    static void stop() {
        if (merchant != null) merchant.close();
        if (psp != null) psp.close();
        merchantDb.stop();
        pspDb.stop();
    }

    @Test
    void cobrancaCriadaEPagaFicaConcluidaComLedgerBalanceado() {
        var charge = merchantApi.post().uri("/charges")
                .body(Map.of("valor", "150.00", "descricao", "Pedido #4821"))
                .retrieve().body(ChargeResponse.class);
        assertThat(charge.status()).isEqualTo("ATIVA");

        var cob = pspApi.get().uri("/cob/{txid}", charge.txid()).retrieve().body(CobResponse.class);
        assertThat(cob.status()).isEqualTo(CobStatus.ATIVA);
        assertThat(cob.valor().original()).isEqualTo("150.00");

        var pix = pspApi.post().uri("/sim/cob/{txid}/pagamento", charge.txid())
                .body(Map.of("infoPagador", "Pedido #4821"))
                .retrieve().body(Pix.class);
        assertThat(pix.endToEndId()).matches("E\\d{8}\\d{12}[a-z0-9]{11}");

        var concluded = await().atMost(Duration.ofSeconds(10)).until(
                () -> merchantApi.get().uri("/charges/{txid}", charge.txid()).retrieve().body(ChargeResponse.class),
                c -> c.status().equals("CONCLUIDA"));
        assertThat(concluded.endToEndIds()).containsExactly(pix.endToEndId());

        var balances = merchantApi.get().uri("/ledger/balances").retrieve().body(BalancesResponse.class);
        assertThat(balances.total()).isEqualTo("0.00");
        assertThat(balances.accounts())
                .containsEntry("psp:pix:liquidar", "150.00")
                .containsEntry("merchant:receita", "-150.00");

        var cobFinal = pspApi.get().uri("/cob/{txid}", charge.txid()).retrieve().body(CobResponse.class);
        assertThat(cobFinal.status()).isEqualTo(CobStatus.CONCLUIDA);
        assertThat(cobFinal.pix()).extracting(Pix::endToEndId).containsExactly(pix.endToEndId());
    }

    private static List<String> datasource(PostgreSQLContainer db) {
        return List.of(
                "--spring.datasource.url=" + db.getJdbcUrl(),
                "--spring.datasource.username=" + db.getUsername(),
                "--spring.datasource.password=" + db.getPassword());
    }
}
