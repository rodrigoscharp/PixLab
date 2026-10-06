package dev.pixlab.merchant.payment;

import static dev.pixlab.merchant.ledger.Posting.credit;
import static dev.pixlab.merchant.ledger.Posting.debit;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.merchant.charge.ChargeEvent;
import dev.pixlab.merchant.charge.ChargeRepository;
import dev.pixlab.merchant.charge.ChargeStatus;
import dev.pixlab.merchant.charge.Transition;
import dev.pixlab.merchant.inbox.InboxEvent;
import dev.pixlab.merchant.inbox.InboxEventHandler;
import dev.pixlab.merchant.inbox.Outcome;
import dev.pixlab.merchant.ledger.Account;
import dev.pixlab.merchant.ledger.Ledger;
import dev.pixlab.merchant.outbox.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Aplica um Pix recebido: transição da cobrança, pagamento, ledger e outbox na transação do processador. */
@Component
public class PixCreditHandler implements InboxEventHandler {

    public static final String SOURCE = "PIX";

    private static final Logger log = LoggerFactory.getLogger(PixCreditHandler.class);

    private final ChargeRepository charges;
    private final PaymentRepository payments;
    private final Ledger ledger;
    private final Outbox outbox;
    private final JsonMapper json;
    private final Clock clock;

    PixCreditHandler(ChargeRepository charges, PaymentRepository payments, Ledger ledger, Outbox outbox,
            JsonMapper json, Clock clock) {
        this.charges = charges;
        this.payments = payments;
        this.ledger = ledger;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public Outcome handle(InboxEvent event) {
        var pix = json.readValue(event.payload(), Pix.class);
        // Defesa em profundidade: a inbox já deduplica, e payment.e2e_id também é UNIQUE.
        if (payments.existsByE2eId(pix.endToEndId())) {
            return Outcome.APPLIED;
        }
        var charge = charges.findByTxid(pix.txid()).orElse(null);
        if (charge == null) {
            return new Outcome.Quarantine("txid desconhecido: " + pix.txid());
        }
        var amount = Valor.parse(pix.valor());
        return switch (charge.apply(new ChargeEvent.PixReceived(amount))) {
            case Transition.Rejected(var reason) -> new Outcome.Quarantine(reason);
            case Transition.Moved(var to) -> {
                payments.save(new Payment(charge.getId(), pix.endToEndId(), amount, Instant.parse(pix.horario()),
                        clock.instant()));
                ledger.post(pix.endToEndId(), List.of(debit(Account.PSP_PIX_LIQUIDAR, amount), credit(creditAccount(to), amount)));
                outbox.add("charge", charge.getTxid(), "pix.recebido", Map.of(
                        "txid", charge.getTxid(), "endToEndId", pix.endToEndId(), "valor", pix.valor(),
                        "status", to.name()));
                log.info("Pix {} aplicado: cobrança {} -> {}", pix.endToEndId(), charge.getTxid(), to);
                yield Outcome.APPLIED;
            }
        };
    }

    // Valor que não bate com a cobrança fica em suspense até análise (a conciliação da F5 abre o caso).
    private static Account creditAccount(ChargeStatus to) {
        return to == ChargeStatus.CONCLUIDA ? Account.RECEITA : Account.SUSPENSE_NAO_IDENTIFICADO;
    }
}
