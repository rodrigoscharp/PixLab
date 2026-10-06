package dev.pixlab.merchant.invariants;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.merchant.charge.Charge;
import dev.pixlab.merchant.charge.ChargeRepository;
import dev.pixlab.merchant.inbox.Inbox;
import dev.pixlab.merchant.inbox.InboxProcessor;
import dev.pixlab.merchant.payment.PixCreditHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * F3: sob qualquer combinação de caos de entrega (duplicata, perda, ordem embaralhada, Pix sem cobrança,
 * processamento intercalado), seguido de consulta ativa, os invariantes 1, 2 e 5 do design doc valem.
 */
class DeliveryChaosProperties {

    /**
     * @param cents      valor da cobrança
     * @param copies     entregas do webhook; 0 = PIX-LOST
     * @param withCharge false = PIX-UNKNOWN (Pix para txid que não é nosso)
     */
    record PixSpec(int cents, int copies, boolean withCharge) {}

    record Delivery(Pix pix) {}

    @Property(tries = 500)
    void invariantesValemSobCaosDeEntrega(@ForAll("scenarios") List<PixSpec> specs, @ForAll long shuffleSeed,
            @ForAll("drainEvery") int drainEvery) {
        var charges = MerchantLab.bean(ChargeRepository.class);
        var inbox = MerchantLab.bean(Inbox.class);
        var processor = MerchantLab.bean(InboxProcessor.class);
        var json = MerchantLab.bean(JsonMapper.class);

        var extrato = new ArrayList<Pix>();
        var deliveries = new ArrayList<Delivery>();
        var now = Instant.now();
        for (var spec : specs) {
            var txid = UUID.randomUUID().toString().replace("-", "");
            var valor = BigDecimal.valueOf(spec.cents(), 2);
            if (spec.withCharge()) {
                charges.save(new Charge(txid, valor, "prop", now, now.plus(1, ChronoUnit.HOURS)));
            }
            var pix = new Pix(e2eId(), txid, valor.toPlainString(), now.toString(), null, List.of());
            extrato.add(pix);
            for (int i = 0; i < spec.copies(); i++) {
                deliveries.add(new Delivery(pix));
            }
        }
        Collections.shuffle(deliveries, new Random(shuffleSeed));

        for (int i = 0; i < deliveries.size(); i++) {
            var pix = deliveries.get(i).pix();
            inbox.offer(PixCreditHandler.SOURCE, pix.endToEndId(), json.writeValueAsString(pix));
            if ((i + 1) % drainEvery == 0) {
                processor.drain();
            }
        }
        // Consulta ativa: tudo que está no extrato do PSP passa de novo pela mesma inbox.
        extrato.forEach(pix -> inbox.offer(PixCreditHandler.SOURCE, pix.endToEndId(), json.writeValueAsString(pix)));
        processor.drain();

        var e2eIds = extrato.stream().map(Pix::endToEndId).toList();
        // Invariante 1 (unicidade) e "nada se perde": cada Pix do extrato gera exatamente um crédito.
        for (var e2eId : e2eIds) {
            assertThat(count("select count(*) from payment where e2e_id = ?", e2eId)).as("pagamentos de %s", e2eId)
                    .isEqualTo(1);
            assertThat(count("select count(*) from ledger_entry where ref = ? and account = 'psp:pix:liquidar'", e2eId))
                    .as("créditos de %s", e2eId).isEqualTo(1);
        }
        // Invariante 2 (partida dobrada), no ledger inteiro.
        assertThat(count("""
                select count(*) from (select tx_id from ledger_entry group by tx_id
                                      having sum(debit) <> sum(credit)) t""")).isZero();
        // Invariante 5 (nada fica preso): todo evento termina PROCESSED ou QUARANTINED.
        assertThat(count("select count(*) from webhook_inbox where status = 'PENDING'")).isZero();
    }

    @Provide
    Arbitrary<List<PixSpec>> scenarios() {
        var spec = Combinators.combine(
                        Arbitraries.integers().between(1, 1_000_000),
                        Arbitraries.integers().between(0, 4),
                        Arbitraries.of(true, true, true, false))
                .as(PixSpec::new);
        return spec.list().ofMinSize(1).ofMaxSize(6);
    }

    @Provide
    Arbitrary<Integer> drainEvery() {
        return Arbitraries.integers().between(1, 5);
    }

    private static long count(String sql, Object... params) {
        var spec = MerchantLab.bean(JdbcClient.class).sql(sql);
        for (var p : params) {
            spec = spec.param(p);
        }
        return spec.query(Long.class).single();
    }

    private static String e2eId() {
        return "E12345678202610061530" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }
}
