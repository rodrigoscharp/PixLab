package dev.pixlab.merchant.payment;

import static dev.pixlab.merchant.ledger.Posting.credit;
import static dev.pixlab.merchant.ledger.Posting.debit;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.merchant.charge.ChargeEvent;
import dev.pixlab.merchant.charge.ChargeRepository;
import dev.pixlab.merchant.charge.ChargeStatus;
import dev.pixlab.merchant.charge.Transition;
import dev.pixlab.merchant.ledger.Account;
import dev.pixlab.merchant.ledger.Ledger;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica um Pix recebido: transição da cobrança, registro do pagamento e lançamento no ledger, numa transação.
 *
 * <p>F1: processamento síncrono dentro do webhook. A F2 troca isso por inbox com {@code UNIQUE} e processador
 * assíncrono (ADR-0002); a F3 adiciona quarentena para eventos sem cobrança ou fora de ordem.
 */
@Service
public class PixReceiver {

    private static final Logger log = LoggerFactory.getLogger(PixReceiver.class);

    private final ChargeRepository charges;
    private final PaymentRepository payments;
    private final Ledger ledger;
    private final Clock clock;

    PixReceiver(ChargeRepository charges, PaymentRepository payments, Ledger ledger, Clock clock) {
        this.charges = charges;
        this.payments = payments;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional
    public void receive(Pix pix) {
        if (payments.existsByE2eId(pix.endToEndId())) {
            log.info("e2eId {} já processado; ignorando", pix.endToEndId());
            return;
        }
        var charge = charges.findByTxid(pix.txid()).orElse(null);
        if (charge == null) {
            log.warn("Pix {} para txid desconhecido {}; ignorado até a quarentena da F3", pix.endToEndId(), pix.txid());
            return;
        }
        var amount = Valor.parse(pix.valor());
        switch (charge.apply(new ChargeEvent.PixReceived(amount))) {
            case Transition.Rejected(var reason) -> log.warn("Pix {} rejeitado: {}", pix.endToEndId(), reason);
            case Transition.Moved(var to) -> {
                payments.save(new Payment(charge.getId(), pix.endToEndId(), amount, Instant.parse(pix.horario()),
                        clock.instant()));
                ledger.post(List.of(debit(Account.PSP_PIX_LIQUIDAR, amount), credit(creditAccount(to), amount)));
                log.info("Pix {} aplicado: cobrança {} -> {}", pix.endToEndId(), charge.getTxid(), to);
            }
        }
    }

    // Valor que não bate com a cobrança fica em suspense até análise (a conciliação da F5 abre o caso).
    private static Account creditAccount(ChargeStatus to) {
        return to == ChargeStatus.CONCLUIDA ? Account.RECEITA : Account.SUSPENSE_NAO_IDENTIFICADO;
    }
}
