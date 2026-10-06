package dev.pixlab.merchant.invariants;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pixlab.contracts.pix.Devolucao;
import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.merchant.TestData;
import dev.pixlab.merchant.charge.Charge;
import dev.pixlab.merchant.charge.ChargeRepository;
import dev.pixlab.merchant.inbox.Inbox;
import dev.pixlab.merchant.inbox.InboxProcessor;
import dev.pixlab.merchant.payment.PixCreditHandler;
import dev.pixlab.merchant.refund.Refund;
import dev.pixlab.merchant.refund.RefundEventHandler;
import dev.pixlab.merchant.refund.RefundEventHandler.RefundEvent;
import dev.pixlab.merchant.refund.RefundService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * F4: invariante 3 (Σ devoluções ≤ valor do pagamento) sob qualquer ordem de eventos: MED antes do crédito
 * (PIX-OOO), devoluções parciais repetidas, falhas (PIX-REFUND-FAIL), duplicatas e ordem embaralhada.
 */
class RefundOrderProperties {

    /**
     * @param cents   valor da devolução
     * @param med     true = MED (chega pronta); false = pedida pelo recebedor
     * @param fails   pedida pelo recebedor e termina NAO_REALIZADO
     * @param copies  quantas vezes a notificação é entregue
     */
    record RefundSpec(int cents, boolean med, boolean fails, int copies) {}

    @Property(tries = 300)
    void devolucoesNuncaPassamDoValorPago(@ForAll("amount") int paidCents, @ForAll("refunds") List<RefundSpec> specs,
            @ForAll long shuffleSeed) {
        var charges = MerchantLab.bean(ChargeRepository.class);
        var inbox = MerchantLab.bean(Inbox.class);
        var processor = MerchantLab.bean(InboxProcessor.class);
        var refunds = MerchantLab.bean(RefundService.class);
        var json = MerchantLab.bean(JsonMapper.class);
        var random = new Random(shuffleSeed);

        var now = Instant.now();
        var txid = TestData.txid();
        var valor = BigDecimal.valueOf(paidCents, 2);
        charges.save(new Charge(txid, valor, "prop", now, now.plus(1, ChronoUnit.HOURS)));
        var e2eId = TestData.e2eId();
        var credit = new Pix(e2eId, txid, valor.toPlainString(), now.toString(), null, List.of());

        // Fase 1: MEDs e o crédito em qualquer ordem (MED pode chegar antes do crédito).
        var phase1 = new ArrayList<Runnable>();
        phase1.add(() -> inbox.offer(PixCreditHandler.SOURCE, e2eId, json.writeValueAsString(credit)));
        // Fase 2: pedidos do recebedor e as notificações deles, embaralhadas com mais MEDs.
        var requests = new ArrayList<RefundSpec>();
        var meds = new ArrayList<Devolucao>();
        int n = 0;
        for (var spec : specs) {
            if (spec.med()) {
                meds.add(new Devolucao("MED" + (n++), "D12345678202610061530" + TestData.random(11),
                        BigDecimal.valueOf(spec.cents(), 2).toPlainString(), Devolucao.MED, Devolucao.DEVOLVIDO,
                        now.toString(), null));
            } else {
                requests.add(spec);
            }
        }
        var half = meds.size() / 2;
        for (var med : meds.subList(0, half)) {
            phase1.add(() -> offer(inbox, json, e2eId, med));
        }
        Collections.shuffle(phase1, random);
        phase1.forEach(Runnable::run);
        processor.drain();

        var phase2 = new ArrayList<Runnable>();
        for (var spec : requests) {
            Refund refund;
            try {
                refund = refunds.request(txid, BigDecimal.valueOf(spec.cents(), 2));
            } catch (ResponseStatusException e) {
                continue; // acima do disponível: recusado na entrada, como deve ser
            }
            var settled = new Devolucao(refund.getRefundId(), "D12345678202610061530" + TestData.random(11),
                    refund.getAmount().toPlainString(), Devolucao.ORIGINAL,
                    spec.fails() ? Devolucao.NAO_REALIZADO : Devolucao.DEVOLVIDO, now.toString(), null);
            for (int i = 0; i < spec.copies(); i++) {
                phase2.add(() -> offer(inbox, json, e2eId, settled));
            }
        }
        for (var med : meds.subList(half, meds.size())) {
            phase2.add(() -> offer(inbox, json, e2eId, med));
            phase2.add(() -> offer(inbox, json, e2eId, med));
        }
        Collections.shuffle(phase2, random);
        phase2.forEach(Runnable::run);
        processor.drain();

        var jdbc = MerchantLab.bean(JdbcClient.class);
        var committed = jdbc.sql("""
                        select coalesce(sum(r.amount), 0) from refund r join payment p on p.id = r.payment_id
                        where p.e2e_id = ? and r.status <> 'NAO_REALIZADO'""")
                .param(e2eId).query(BigDecimal.class).single();
        // Invariante 3.
        assertThat(committed).isLessThanOrEqualTo(valor);
        // O ledger conta a mesma história: o que saiu em devoluções é exatamente o que não falhou.
        var refundedInLedger = jdbc.sql("""
                        select coalesce(sum(l.debit - l.credit), 0) from ledger_entry l
                        join payment p on l.ref like p.e2e_id || ':%'
                        where p.e2e_id = ? and l.account = 'merchant:devolucoes'""")
                .param(e2eId).query(BigDecimal.class).single();
        assertThat(refundedInLedger).isEqualByComparingTo(committed);
        assertThat(jdbc.sql("""
                select count(*) from (select tx_id from ledger_entry group by tx_id
                                      having sum(debit) <> sum(credit)) t""").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from webhook_inbox where status = 'PENDING'").query(Long.class).single())
                .isZero();
    }

    @Provide
    Arbitrary<Integer> amount() {
        return Arbitraries.integers().between(100, 100_000);
    }

    @Provide
    Arbitrary<List<RefundSpec>> refunds() {
        return Combinators.combine(
                        Arbitraries.integers().between(1, 60_000),
                        Arbitraries.of(true, false, false),
                        Arbitraries.of(true, false, false, false),
                        Arbitraries.integers().between(1, 3))
                .as(RefundSpec::new)
                .list().ofMinSize(1).ofMaxSize(8);
    }

    private static void offer(Inbox inbox, JsonMapper json, String e2eId, Devolucao d) {
        var event = new RefundEvent(e2eId, d);
        inbox.offer(RefundEventHandler.SOURCE, event.key(), json.writeValueAsString(event));
    }
}
