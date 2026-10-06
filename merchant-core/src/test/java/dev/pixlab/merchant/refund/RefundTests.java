package dev.pixlab.merchant.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.pixlab.contracts.pix.Devolucao;
import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.merchant.TestcontainersConfiguration;
import dev.pixlab.merchant.charge.Charge;
import dev.pixlab.merchant.charge.ChargeRepository;
import dev.pixlab.merchant.charge.ChargeStatus;
import dev.pixlab.merchant.inbox.Inbox;
import dev.pixlab.merchant.payment.PixCreditHandler;
import dev.pixlab.merchant.TestData;
import dev.pixlab.merchant.psp.PspClient;
import dev.pixlab.merchant.refund.RefundEventHandler.RefundEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RefundTests {

    @Autowired
    RefundService refunds;

    @Autowired
    RefundRepository refundRepository;

    @Autowired
    ChargeRepository charges;

    @Autowired
    Inbox inbox;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    PspClient psp;

    @Test
    void devolucaoParcialEDepoisTotal() {
        var paid = paidCharge("100.00");

        var first = refunds.request(paid.txid(), new BigDecimal("40.00"));
        assertThat(first.getStatus()).isEqualTo(RefundStatus.EM_PROCESSAMENTO);
        assertThat(net(paid, first, "merchant:devolucoes")).isEqualByComparingTo("40.00");

        settle(paid, first, Devolucao.DEVOLVIDO);
        awaitCharge(paid, ChargeStatus.PARCIALMENTE_DEVOLVIDA);

        var second = refunds.request(paid.txid(), new BigDecimal("60.00"));
        settle(paid, second, Devolucao.DEVOLVIDO);
        awaitCharge(paid, ChargeStatus.DEVOLVIDA);
    }

    @Test
    void naoRealizadoEstornaOLancamentoProvisorio() {
        var paid = paidCharge("100.00");
        var refund = refunds.request(paid.txid(), new BigDecimal("30.00"));

        settle(paid, refund, Devolucao.NAO_REALIZADO);

        await().untilAsserted(() -> assertThat(status(refund)).isEqualTo(RefundStatus.NAO_REALIZADO));
        assertThat(net(paid, refund, "merchant:devolucoes")).isEqualByComparingTo("0.00");
        assertThat(net(paid, refund, "psp:pix:liquidar")).isEqualByComparingTo("0.00");
        assertThat(charges.findByTxid(paid.txid()).orElseThrow().getStatus()).isEqualTo(ChargeStatus.CONCLUIDA);
    }

    @Test
    void naoDevolveMaisQueOValorPago() {
        var paid = paidCharge("100.00");
        refunds.request(paid.txid(), new BigDecimal("70.00"));

        assertThatThrownBy(() -> refunds.request(paid.txid(), new BigDecimal("30.01")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("excede");
    }

    @Test
    void devolucoesConcorrentesRespeitamOLimite() throws Exception {
        var paid = paidCharge("100.00");
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 25; i++) {
            tasks.add(() -> {
                try {
                    refunds.request(paid.txid(), new BigDecimal("10.00"));
                    return true;
                } catch (ResponseStatusException e) {
                    return false;
                }
            });
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var accepted = pool.invokeAll(tasks).stream().filter(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).count();
            assertThat(accepted).isEqualTo(10);
        }
        assertThat(jdbc.sql("select sum(r.amount) from refund r join payment p on p.id = r.payment_id where p.e2e_id = ?")
                .param(paid.e2eId()).query(BigDecimal.class).single()).isEqualByComparingTo("100.00");
    }

    @Test
    void medChegaSemPedidoEDebita() {
        var paid = paidCharge("100.00");

        offerRefund(paid.e2eId(), new Devolucao("MEDabc123", "D12345678202610061530medmedmed1", "25.00", Devolucao.MED,
                Devolucao.DEVOLVIDO, Instant.now().toString(), "MED"));

        awaitCharge(paid, ChargeStatus.PARCIALMENTE_DEVOLVIDA);
        assertThat(net(RefundService.ledgerRef(paid.e2eId(), "MEDabc123"), "merchant:devolucoes")).isEqualByComparingTo("25.00");
    }

    @Test
    void devolucaoAntesDoCreditoEsperaOCreditoChegar() {
        var txid = UUID.randomUUID().toString().replace("-", "");
        var now = Instant.now();
        charges.save(new Charge(txid, new BigDecimal("80.00"), "ooo", now, now.plus(1, ChronoUnit.HOURS)));
        var e2eId = TestData.e2eId();

        // PIX-OOO: o MED chega primeiro.
        offerRefund(e2eId, new Devolucao("MEDooo1", "D12345678202610061530oooooooooo1", "80.00", Devolucao.MED,
                Devolucao.DEVOLVIDO, now.toString(), "MED"));
        await().untilAsserted(() -> assertThat(jdbc.sql(
                "select attempts from webhook_inbox where event_key like ?").param(e2eId + ":%")
                .query(Integer.class).single()).isPositive());

        inbox.offer(PixCreditHandler.SOURCE, e2eId,
                json.writeValueAsString(new Pix(e2eId, txid, "80.00", now.toString(), null, List.of())));

        await().untilAsserted(() ->
                assertThat(charges.findByTxid(txid).orElseThrow().getStatus()).isEqualTo(ChargeStatus.DEVOLVIDA));
    }

    record Paid(String txid, String e2eId) {}

    private Paid paidCharge(String valor) {
        var txid = UUID.randomUUID().toString().replace("-", "");
        var now = Instant.now();
        charges.save(new Charge(txid, new BigDecimal(valor), "refund", now, now.plus(1, ChronoUnit.HOURS)));
        var e2eId = TestData.e2eId();
        inbox.offer(PixCreditHandler.SOURCE, e2eId,
                json.writeValueAsString(new Pix(e2eId, txid, valor, now.toString(), null, List.of())));
        await().untilAsserted(() ->
                assertThat(charges.findByTxid(txid).orElseThrow().getStatus()).isEqualTo(ChargeStatus.CONCLUIDA));
        return new Paid(txid, e2eId);
    }

    private void settle(Paid paid, Refund refund, String status) {
        offerRefund(paid.e2eId(), new Devolucao(refund.getRefundId(), "D12345678202610061530" + TestData.random(11),
                refund.getAmount().toPlainString(), Devolucao.ORIGINAL, status, Instant.now().toString(), null));
    }

    private void offerRefund(String e2eId, Devolucao d) {
        var event = new RefundEvent(e2eId, d);
        inbox.offer(RefundEventHandler.SOURCE, event.key(), json.writeValueAsString(event));
    }

    private RefundStatus status(Refund refund) {
        return RefundStatus.valueOf(jdbc.sql("select status from refund where refund_id = ?")
                .param(refund.getRefundId()).query(String.class).single());
    }

    private void awaitCharge(Paid paid, ChargeStatus expected) {
        await().untilAsserted(() ->
                assertThat(charges.findByTxid(paid.txid()).orElseThrow().getStatus()).isEqualTo(expected));
    }

    private BigDecimal net(Paid paid, Refund refund, String account) {
        return net(RefundService.ledgerRef(paid.e2eId(), refund.getRefundId()), account);
    }

    private BigDecimal net(String ref, String account) {
        return jdbc.sql("select coalesce(sum(debit - credit), 0) from ledger_entry where ref = ? and account = ?")
                .param(ref).param(account).query(BigDecimal.class).single();
    }

}
