package dev.pixlab.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pixlab.contracts.pix.Pix;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** F4 — devoluções parciais, falha, MED e devolução antes do crédito, de ponta a ponta. */
class RefundE2ETest {

    record ChargeResponse(String txid, String status, List<String> endToEndIds) {}

    record RefundResponse(String id, String valor, String status) {}

    static RestClient psp;
    static RestClient merchant;

    @BeforeAll
    static void start() throws Exception {
        psp = Lab.get().pspApi;
        merchant = Lab.get().merchantApi;
    }

    @AfterEach
    void reset() {
        psp.delete().uri("/sim/chaos").retrieve().toBodilessEntity();
    }

    @Test
    void devolucoesParciaisAteDevolverTudo() {
        var paid = paidCharge("100.00");

        refund(paid.txid(), "30.00");
        advanceUntil(paid.txid(), "PARCIALMENTE_DEVOLVIDA");
        refund(paid.txid(), "70.00");
        advanceUntil(paid.txid(), "DEVOLVIDA");
        assertThat(total()).isEqualTo("0.00");
    }

    @Test
    void devolucaoRecusadaPeloPspEstornaELiberaOSaldo() {
        psp.put().uri("/sim/chaos/profiles/refund-fail?seed=3").retrieve().toBodilessEntity();
        var paid = paidCharge("50.00");

        var refund = refund(paid.txid(), "50.00");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            advance();
            var refunds = refunds(paid.e2eId());
            assertThat(refunds).containsEntry(refund.id(), "NAO_REALIZADO").containsEntry("devolucoes", "0.00");
        });
        assertThat(merchant.get().uri("/charges/{txid}", paid.txid()).retrieve().body(ChargeResponse.class).status())
                .isEqualTo("CONCLUIDA");
        assertThat(refund.status()).isEqualTo("EM_PROCESSAMENTO");
    }

    @Test
    void medDebitaSemPedidoDoRecebedor() {
        var paid = paidCharge("80.00");

        psp.post().uri("/sim/pix/{e2e}/med", paid.e2eId()).body(Map.of("valor", "20.00")).retrieve().toBodilessEntity();

        advanceUntil(paid.txid(), "PARCIALMENTE_DEVOLVIDA");
    }

    @Test
    void medAntesDoCreditoEsperaOCredito() {
        psp.put().uri("/sim/chaos/profiles/ooo?seed=5").retrieve().toBodilessEntity();
        var charge = merchant.post().uri("/charges").body(Map.of("valor", "60.00")).retrieve().body(ChargeResponse.class);
        var pix = psp.post().uri("/sim/cob/{txid}/pagamento", charge.txid()).retrieve().body(Pix.class);

        // O crédito está atrasado 30 min no relógio do PSP; o MED é notificado na hora.
        psp.post().uri("/sim/pix/{e2e}/med", pix.endToEndId()).retrieve().toBodilessEntity();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(merchant.get().uri("/admin/inbox").retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Long>>() {}).get("PENDING")).isPositive());
        assertThat(merchant.get().uri("/charges/{txid}", charge.txid()).retrieve().body(ChargeResponse.class).status())
                .isEqualTo("ATIVA");

        psp.post().uri("/sim/clock/advance").body(Map.of("duration", "PT31M")).retrieve().toBodilessEntity();
        advanceUntil(charge.txid(), "DEVOLVIDA");
    }

    record Paid(String txid, String e2eId) {}

    private Paid paidCharge(String valor) {
        var charge = merchant.post().uri("/charges").body(Map.of("valor", valor)).retrieve().body(ChargeResponse.class);
        var pix = psp.post().uri("/sim/cob/{txid}/pagamento", charge.txid()).retrieve().body(Pix.class);
        advanceUntil(charge.txid(), "CONCLUIDA");
        return new Paid(charge.txid(), pix.endToEndId());
    }

    private RefundResponse refund(String txid, String valor) {
        return merchant.post().uri("/charges/{txid}/refunds", txid).body(Map.of("valor", valor)).retrieve()
                .body(RefundResponse.class);
    }

    private void advanceUntil(String txid, String status) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until(() -> {
            advance();
            return merchant.get().uri("/charges/{txid}", txid).retrieve().body(ChargeResponse.class).status()
                    .equals(status);
        });
    }

    private void advance() {
        psp.post().uri("/sim/clock/advance").body(Map.of("duration", "PT5S")).retrieve().toBodilessEntity();
    }

    private Map<String, String> refunds(String e2eId) {
        return merchant.get().uri("/admin/refunds/{e2e}", e2eId).retrieve()
                .body(new ParameterizedTypeReference<Map<String, String>>() {});
    }

    private String total() {
        return (String) merchant.get().uri("/ledger/balances").retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {}).get("total");
    }
}
