package dev.pixlab.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pixlab.contracts.pix.PixListResponse;
import dev.pixlab.contracts.pix.Pix;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

    record Paid(String txid, String e2eId, BigDecimal valor) {}

    private ChaosRun() {}

    /**
     * Deixa o tempo virtual do dispatcher passar até não sobrar entrega pendente (atrasos de horas e backoffs vencem
     * em segundos), roda a consulta ativa (webhook é otimização: ela traz o que se perdeu) e espera a inbox esvaziar.
     */
    private static void settle(Lab lab, Instant inicio) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
            lab.pspApi.post().uri("/sim/clock/advance").body(Map.of("duration", "PT15M")).retrieve().toBodilessEntity();
            return lab.pspApi.get().uri("/sim/webhook-deliveries").retrieve().body(Deliveries.class).pending() == 0;
        });
        lab.merchantApi.post().uri("/admin/consulta-ativa")
                .body(Map.of("inicio", inicio.toString(), "fim", Instant.now().plusSeconds(1).toString()))
                .retrieve().toBodilessEntity();
        await().atMost(Duration.ofSeconds(30)).until(() -> lab.merchantApi.get().uri("/admin/inbox")
                .retrieve().body(new ParameterizedTypeReference<Map<String, Long>>() {}).get("PENDING") == 0);
    }

    private static String status(Lab lab, String txid) {
        return lab.merchantApi.get().uri("/charges/{txid}", txid).retrieve().body(ChargeResponse.class).status();
    }

    static Report run(String profile, long seed, int payments) throws Exception {
        var lab = Lab.get();
        var psp = lab.pspApi;
        var merchant = lab.merchantApi;
        System.out.printf("[chaos] perfil=%s seed=%d pagamentos=%d (reproduza com ./gradlew chaos --chaos-profile=%s --seed=%d)%n",
                profile, seed, payments, profile, seed);

        psp.put().uri("/sim/chaos/profiles/{name}?seed={seed}", profile, seed).retrieve().toBodilessEntity();
        var inicio = Instant.now().minusSeconds(1);
        var paid = new ArrayList<Paid>();
        var random = new Random(seed);
        try {
            for (int i = 0; i < payments; i++) {
                var valor = new BigDecimal("%d.%02d".formatted(10 + i, i % 100));
                var charge = merchant.post().uri("/charges")
                        .body(Map.of("valor", valor.toPlainString(), "descricao", "caos " + i))
                        .retrieve().body(ChargeResponse.class);
                var pix = psp.post().uri("/sim/cob/{txid}/pagamento", charge.txid()).retrieve().body(Pix.class);
                paid.add(new Paid(charge.txid(), pix.endToEndId(), valor));
            }
            settle(lab, inicio);

            // Devoluções (F4): parte pedida pelo recebedor, parte via MED, sob o mesmo perfil de caos.
            for (var p : paid) {
                var roll = random.nextInt(4);
                if (roll == 0 && "CONCLUIDA".equals(status(lab, p.txid()))) {
                    merchant.post().uri("/charges/{txid}/refunds", p.txid())
                            .body(Map.of("valor", p.valor().divide(BigDecimal.TWO, 2, RoundingMode.DOWN).toPlainString()))
                            .retrieve().toBodilessEntity();
                } else if (roll == 1) {
                    psp.post().uri("/sim/pix/{e2e}/med", p.e2eId()).retrieve().toBodilessEntity();
                }
            }
        } finally {
            psp.delete().uri("/sim/chaos").retrieve().toBodilessEntity();
        }
        settle(lab, inicio);

        var fim = Instant.now().plusSeconds(1);
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
        for (var p : paid) {
            assertThat(status(lab, p.txid())).as("cobrança %s (seed %d)", p.txid(), seed)
                    .isIn("CONCLUIDA", "DIVERGENTE", "PARCIALMENTE_DEVOLVIDA", "DEVOLVIDA");
            // Invariante 3: o que saiu em devoluções nunca passa do que entrou.
            var refunded = new BigDecimal(merchant.get().uri("/admin/refunds/{e2e}", p.e2eId()).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, String>>() {}).get("devolucoes"));
            assertThat(refunded).as("devolvido do e2eId %s (seed %d)", p.e2eId(), seed).isLessThanOrEqualTo(p.valor());
        }
        // Invariante 2: ledger balanceado.
        var total = merchant.get().uri("/ledger/balances").retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {}).get("total");
        assertThat(total).as("total do ledger (seed %d)", seed).isEqualTo("0.00");

        System.out.printf("[chaos] ok: %d pagamentos, %d Pix no extrato%n", payments, extrato.size());
        return new Report(profile, seed, payments, extrato.size());
    }
}
