package dev.pixlab.merchant.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.contracts.pix.PixListResponse;
import dev.pixlab.contracts.pix.PixListResponse.Paginacao;
import dev.pixlab.contracts.pix.PixListResponse.Parametros;
import dev.pixlab.merchant.TestData;
import dev.pixlab.merchant.TestcontainersConfiguration;
import dev.pixlab.merchant.charge.Charge;
import dev.pixlab.merchant.charge.ChargeRepository;
import dev.pixlab.merchant.charge.ChargeStatus;
import dev.pixlab.merchant.inbox.Inbox;
import dev.pixlab.merchant.inbox.InboxProcessor;
import dev.pixlab.merchant.payment.PixCreditHandler;
import dev.pixlab.merchant.psp.PspClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

/** F5: cada classificação da conciliação, auto-reparo, ajuste idempotente e restart após falha. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"pixlab.inbox.workers=0", "pixlab.recon.chunk-size=2", "pixlab.recon.page-size=3"})
class ReconTests {

    @Autowired
    ReconService recon;

    @Autowired
    ReconRuns runs;

    @Autowired
    ChargeRepository charges;

    @Autowired
    Inbox inbox;

    @Autowired
    InboxProcessor processor;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    PspClient psp;

    Instant base = Instant.parse("2031-01-01T00:00:00Z").plus((long) (Math.random() * 500_000), ChronoUnit.MINUTES);

    @Test
    void classificaReparaEFechaNaSegundaExecucao() throws Exception {
        var ok = charge("10.00");
        var ledgerOk = pix(ok, "10.00", 1);
        var lost = charge("20.00");
        var pspLost = pix(lost, "20.00", 2);
        var divergent = charge("30.00");
        var pspDivergent = pix(divergent, "30.00", 3);
        var unknown = new Pix(TestData.e2eId(), "naoexiste" + TestData.random(20), "40.00", at(4), null, List.of());
        var dupFirst = charge("50.00");
        var pspDup1 = pix(dupFirst, "50.00", 5);
        var pspDup2 = pix(dupFirst, "50.00", 6);
        var phantom = pix(charge("60.00"), "60.00", 7);

        deliver(ledgerOk, pspDup1, pspDup2, unknown, phantom);
        deliver(new Pix(pspDivergent.endToEndId(), pspDivergent.txid(), "31.50", pspDivergent.horario(), null, List.of()));
        processor.drain();
        extrato(List.of(ledgerOk, pspLost, pspDivergent, unknown, pspDup1, pspDup2));

        var first = recon.run(base, base.plus(1, ChronoUnit.HOURS));

        assertThat(first.status()).isEqualTo(BatchStatus.COMPLETED.name());
        assertThat(results(first.id())).containsExactlyInAnyOrderEntriesOf(Map.of(
                ledgerOk.endToEndId(), "OK",
                pspLost.endToEndId(), "FALTA_NO_LEDGER",
                pspDivergent.endToEndId(), "VALOR_DIVERGENTE",
                unknown.endToEndId(), "SEM_COBRANCA",
                pspDup1.endToEndId(), "OK",
                pspDup2.endToEndId(), "DUPLICADO",
                phantom.endToEndId(), "FALTA_NO_PSP"));

        processor.drain();
        assertThat(charges.findByTxid(lost).orElseThrow().getStatus()).isEqualTo(ChargeStatus.CONCLUIDA);

        var second = recon.run(base, base.plus(1, ChronoUnit.HOURS));
        assertThat(results(second.id())).containsEntry(pspLost.endToEndId(), "OK")
                .containsEntry(pspDivergent.endToEndId(), "VALOR_DIVERGENTE");
        // O ajuste não se repete: um único lançamento de ajuste para o e2eId divergente.
        assertThat(jdbc.sql("select count(distinct tx_id) from ledger_entry where ref = ?")
                .param("recon:" + pspDivergent.endToEndId()).query(Long.class).single()).isEqualTo(1);
        // Invariante 4 (excluindo o crédito fantasma, que é alerta): Σ ledger = Σ extrato.
        assertThat(second.summary().get("pspTotal")).isEqualTo(second.summary().get("ledgerTotal"));
    }

    @Test
    void falhaNoMeioERestartContinuaSemDuplicarEfeitos() throws Exception {
        var pixes = new java.util.ArrayList<Pix>();
        for (int i = 0; i < 5; i++) {
            pixes.add(pix(charge("10.00"), "10.00", i));
        }
        var calls = new AtomicInteger();
        given(psp.listarPixPagina(any(), any(), eq(0), anyInt())).willReturn(page(pixes.subList(0, 3), 0, 2));
        given(psp.listarPixPagina(any(), any(), eq(1), anyInt())).willAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                throw new ResourceAccessException("PSP fora do ar");
            }
            return page(pixes.subList(3, 5), 1, 2);
        });

        var failed = recon.run(base, base.plus(1, ChronoUnit.HOURS));
        assertThat(failed.status()).isEqualTo(BatchStatus.FAILED.name());
        assertThat(runs.items(failed.id())).hasSize(2);

        var restarted = recon.restart(failed.id());
        assertThat(restarted.status()).isEqualTo(BatchStatus.COMPLETED.name());
        assertThat(runs.items(failed.id())).extracting(ReconRuns.Item::key)
                .containsExactlyInAnyOrderElementsOf(pixes.stream().map(Pix::endToEndId).toList());
        assertThat(jdbc.sql("select count(*) from webhook_inbox where event_key in (:keys)")
                .param("keys", pixes.stream().map(Pix::endToEndId).toList()).query(Long.class).single()).isEqualTo(5);
    }

    private void extrato(List<Pix> pix) {
        given(psp.listarPixPagina(any(), any(), anyInt(), anyInt())).willAnswer(inv -> {
            int page = inv.getArgument(2);
            int size = inv.getArgument(3);
            int from = Math.min(page * size, pix.size());
            int to = Math.min(from + size, pix.size());
            return page(pix.subList(from, to), page, (pix.size() + size - 1) / size);
        });
    }

    private static PixListResponse page(List<Pix> pix, int page, int pages) {
        return new PixListResponse(new Parametros(null, null, new Paginacao(page, pix.size(), pages, 0)), pix);
    }

    private Map<String, String> results(long runId) {
        var map = new java.util.HashMap<String, String>();
        runs.items(runId).forEach(i -> map.put(i.key(), i.result()));
        return map;
    }

    private void deliver(Pix... pix) {
        for (var p : pix) {
            inbox.offer(PixCreditHandler.SOURCE, p.endToEndId(), json.writeValueAsString(p));
        }
    }

    private String charge(String valor) {
        var txid = TestData.txid();
        charges.save(new Charge(txid, new BigDecimal(valor), "recon", base, base.plus(1, ChronoUnit.HOURS)));
        return txid;
    }

    private Pix pix(String txid, String valor, int minute) {
        return new Pix(TestData.e2eId(), txid, valor, at(minute), null, List.of());
    }

    private String at(int minute) {
        return base.plus(minute, ChronoUnit.MINUTES).toString();
    }
}
