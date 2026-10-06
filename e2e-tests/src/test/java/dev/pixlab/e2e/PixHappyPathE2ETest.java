package dev.pixlab.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pixlab.contracts.pix.CobResponse;
import dev.pixlab.contracts.pix.CobStatus;
import dev.pixlab.contracts.pix.Pix;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * F1 — Pix caminho feliz, de ponta a ponta: recebedor cria a cobrança no PSP, o pagador simulado paga,
 * o PSP entrega o webhook, e o recebedor conclui a cobrança com o ledger balanceado.
 */
class PixHappyPathE2ETest {

    record ChargeResponse(String txid, String valor, String status, List<String> endToEndIds) {}

    record BalancesResponse(Map<String, String> accounts, String total) {}

    static RestClient pspApi;
    static RestClient merchantApi;

    @BeforeAll
    static void start() throws Exception {
        pspApi = Lab.get().pspApi;
        merchantApi = Lab.get().merchantApi;
        pspApi.delete().uri("/sim/chaos").retrieve().toBodilessEntity();
    }

    @Test
    void cobrancaCriadaEPagaFicaConcluidaComLedgerBalanceado() {
        var before = merchantApi.get().uri("/ledger/balances").retrieve().body(BalancesResponse.class);

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

        var after = merchantApi.get().uri("/ledger/balances").retrieve().body(BalancesResponse.class);
        assertThat(after.total()).isEqualTo("0.00");
        assertThat(delta(before, after, "psp:pix:liquidar")).isEqualByComparingTo("150.00");
        assertThat(delta(before, after, "merchant:receita")).isEqualByComparingTo("-150.00");

        var cobFinal = pspApi.get().uri("/cob/{txid}", charge.txid()).retrieve().body(CobResponse.class);
        assertThat(cobFinal.status()).isEqualTo(CobStatus.CONCLUIDA);
        assertThat(cobFinal.pix()).extracting(Pix::endToEndId).containsExactly(pix.endToEndId());
    }

    static BigDecimal delta(BalancesResponse before, BalancesResponse after, String account) {
        return new BigDecimal(after.accounts().getOrDefault(account, "0"))
                .subtract(new BigDecimal(before.accounts().getOrDefault(account, "0")));
    }
}
