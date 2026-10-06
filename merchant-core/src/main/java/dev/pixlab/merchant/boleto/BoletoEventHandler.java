package dev.pixlab.merchant.boleto;

import static dev.pixlab.merchant.ledger.Posting.credit;
import static dev.pixlab.merchant.ledger.Posting.debit;

import dev.pixlab.contracts.boleto.Cnab240;
import dev.pixlab.contracts.boleto.Cnab240.Ocorrencia;
import dev.pixlab.contracts.boleto.Encargos;
import dev.pixlab.merchant.charge.Charge;
import dev.pixlab.merchant.charge.ChargeEvent;
import dev.pixlab.merchant.charge.ChargeRepository;
import dev.pixlab.merchant.charge.ChargeStatus;
import dev.pixlab.merchant.charge.Transition;
import dev.pixlab.merchant.inbox.InboxEvent;
import dev.pixlab.merchant.inbox.InboxEventHandler;
import dev.pixlab.merchant.inbox.Outcome;
import dev.pixlab.merchant.ledger.Account;
import dev.pixlab.merchant.ledger.Ledger;
import dev.pixlab.merchant.ledger.Posting;
import dev.pixlab.merchant.outbox.Outbox;
import dev.pixlab.merchant.payment.Payment;
import dev.pixlab.merchant.payment.PaymentRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ocorrências de boleto do arquivo de retorno ({@code source = BOLETO}, chave {@code nossoNumero:idOcorrencia}).
 * Política de valores no ADR-0008.
 */
@Component
public class BoletoEventHandler implements InboxEventHandler {

    public static final String SOURCE = "BOLETO";

    private static final Logger log = LoggerFactory.getLogger(BoletoEventHandler.class);

    private final ChargeRepository charges;
    private final PaymentRepository payments;
    private final Ledger ledger;
    private final Outbox outbox;
    private final JsonMapper json;
    private final Clock clock;

    BoletoEventHandler(ChargeRepository charges, PaymentRepository payments, Ledger ledger, Outbox outbox,
            JsonMapper json, Clock clock) {
        this.charges = charges;
        this.payments = payments;
        this.ledger = ledger;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    public static String key(Ocorrencia o) {
        return o.nossoNumero() + ":" + o.idOcorrencia();
    }

    public static String ledgerRef(String key) {
        return "BOL:" + key;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public Outcome handle(InboxEvent event) {
        var o = json.readValue(event.payload(), Ocorrencia.class);
        var charge = charges.findByNossoNumero(o.nossoNumero()).orElse(null);
        return switch (o.codigo()) {
            case Cnab240.ENTRADA_CONFIRMADA -> Outcome.APPLIED;
            case Cnab240.BAIXA -> {
                if (charge != null) {
                    charge.apply(new ChargeEvent.BoletoCancelled());
                }
                yield Outcome.APPLIED;
            }
            case Cnab240.LIQUIDACAO -> liquidar(event.eventKey(), o, charge);
            default -> new Outcome.Quarantine("ocorrência não tratada: " + o.codigo());
        };
    }

    private Outcome liquidar(String key, Ocorrencia o, Charge charge) {
        var ref = ledgerRef(key);
        if (payments.existsByBoletoRef(ref)) {
            return Outcome.APPLIED;
        }
        var paid = o.valorPago();
        var paidAt = o.dataOcorrencia().atStartOfDay(Charge.SAO_PAULO).toInstant();
        var postings = new ArrayList<Posting>();
        postings.add(debit(Account.BANCO_BOLETO_LIQUIDAR, paid));
        String type;
        if (charge == null) {
            postings.add(credit(Account.SUSPENSE_NAO_IDENTIFICADO, paid));
            type = "boleto.sem_cobranca";
        } else {
            var expected = Encargos.devido(charge.getAmount(), charge.getDueDate(), charge.getFinePct(),
                    charge.getInterestPct(), o.dataOcorrencia());
            switch (charge.apply(new ChargeEvent.BoletoPaid(paid, expected))) {
                case Transition.Moved(var to) when to == ChargeStatus.CONCLUIDA -> {
                    postings.add(credit(Account.RECEITA, expected));
                    var excess = paid.subtract(expected);
                    if (excess.signum() > 0) {
                        postings.add(credit(Account.CREDITO_A_DEVOLVER, excess));
                        type = "boleto.pago_a_maior";
                    } else {
                        type = "boleto.liquidado";
                    }
                }
                case Transition.Moved(var to) -> {
                    postings.add(credit(Account.SUSPENSE_NAO_IDENTIFICADO, paid));
                    type = "boleto.pago_a_menor";
                }
                case Transition.Rejected(var reason) -> {
                    postings.add(credit(Account.CREDITO_A_DEVOLVER, paid));
                    type = "boleto.pago_em_duplicidade";
                }
            }
            log.info("Boleto {} ({}): pago {} devido {} -> {}", o.nossoNumero(), key, paid, expected, type);
        }
        payments.save(Payment.boleto(charge == null ? null : charge.getId(), ref, paid, paidAt, clock.instant()));
        ledger.post(ref, postings);
        outbox.add("boleto", o.nossoNumero(), type, Map.of("nossoNumero", o.nossoNumero(), "ocorrencia",
                o.idOcorrencia(), "valorPago", paid.toPlainString()));
        return Outcome.APPLIED;
    }
}
