package dev.pixlab.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pixlab.contracts.pix.PixListResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Uma execução de caos: aplica um perfil com seed no PSP, paga N cobranças, deixa o tempo virtual passar até
 * todas as entregas terminarem, roda a consulta ativa e confere os invariantes no recebedor.
 */
final class ChaosRun {

    record ChargeResponse(String txid, String status) {}

    record Deliveries(long pending) {}

    record PaymentView(String endToEndId, String txid, String valor, List<Map<String, String>> ledger) {}

    record Report(String profile, long seed, int payments, int extrato) {}

    private ChaosRun() {}

    static Report run(String profile, long seed, int payments) throws Exception {
        var lab = Lab.get();
        var psp = lab.pspApi;
        var merchant = lab.merchantApi;
        System.out.printf("[chaos] perfil=%s seed=%d pagamentos=%d (reproduza com ./gradlew chaos --chaos-profile=%s --seed=%d)%n",
                profile, seed, payments, profile, seed);

        psp.put().uri("/sim/chaos/profiles/{name}?seed={seed}", profile, seed).retrieve().toBodilessEntity();
        var inicio = Instant.now().minusSeconds(1);
        var paid = new ArrayList<String>();
        try {
            for (int i = 0; i < payments; i++) {
                var charge = merchant.post().uri("/charges")
                        .body(Map.of("valor", "%d.%02d".formatted(10 + i, i % 100), "descricao", "caos " + i))
                        .retrieve().body(ChargeResponse.class);
                psp.post().uri("/sim/cob/{txid}/pagamento", charge.txid()).retrieve().toBodilessEntity();
                paid.add(charge.txid());
            }
        } finally {
            psp.delete().uri("/sim/chaos").retrieve().toBodilessEntity();
        }

        // Tempo virtual do dispatcher: atrasos de horas e backoffs vencem em segundos.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
            psp.post().uri("/sim/clock/advance").body(Map.of("duration", "PT15M")).retrieve().toBodilessEntity();
            return psp.get().uri("/sim/webhook-deliveries").retrieve().body(Deliveries.class).pending() == 0;
        });
        var fim = Instant.now().plusSeconds(1);

        // Webhook é otimização: a consulta ativa traz o que se perdeu.
        merchant.post().uri("/admin/consulta-ativa").body(Map.of("inicio", inicio.toString(), "fim", fim.toString()))
                .retrieve().toBodilessEntity();
        await().atMost(Duration.ofSeconds(30)).until(() -> merchant.get().uri("/admin/inbox")
                .retrieve().body(new ParameterizedTypeReference<Map<String, Long>>() {}).get("PENDING") == 0);

        var extrato = psp.get().uri("/pix?inicio={i}&fim={f}&paginacao.itensPorPagina=1000", inicio, fim)
                .retrieve().body(PixListResponse.class).pix();
        assertThat(extrato).as("extrato do PSP").hasSizeGreaterThanOrEqualTo(payments);

        for (var pix : extrato) {
            var views = merchant.get().uri("/admin/payments/{e2e}", pix.endToEndId()).retrieve()
                    .body(new ParameterizedTypeReference<List<PaymentView>>() {});
            // Invariante 1 + nada se perde: cada Pix do extrato vira exatamente um pagamento com um crédito.
            assertThat(views).as("pagamentos do e2eId %s (seed %d)", pix.endToEndId(), seed).hasSize(1);
            assertThat(views.getFirst().ledger()).as("lançamentos do e2eId %s (seed %d)", pix.endToEndId(), seed)
                    .hasSize(2);
        }
        for (var txid : paid) {
            var status = merchant.get().uri("/charges/{txid}", txid).retrieve().body(ChargeResponse.class).status();
            assertThat(status).as("cobrança %s (seed %d)", txid, seed).isIn("CONCLUIDA", "DIVERGENTE");
        }
        // Invariante 2: ledger balanceado.
        var total = merchant.get().uri("/ledger/balances").retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {}).get("total");
        assertThat(total).as("total do ledger (seed %d)", seed).isEqualTo("0.00");

        System.out.printf("[chaos] ok: %d pagamentos, %d Pix no extrato%n", payments, extrato.size());
        return new Report(profile, seed, payments, extrato.size());
    }
}
