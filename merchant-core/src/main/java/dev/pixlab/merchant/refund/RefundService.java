package dev.pixlab.merchant.refund;

import static dev.pixlab.merchant.ledger.Posting.credit;
import static dev.pixlab.merchant.ledger.Posting.debit;

import dev.pixlab.contracts.pix.DevolucaoRequest;
import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.merchant.charge.ChargeEvent;
import dev.pixlab.merchant.charge.ChargeRepository;
import dev.pixlab.merchant.charge.Transition;
import dev.pixlab.merchant.ledger.Account;
import dev.pixlab.merchant.ledger.Ledger;
import dev.pixlab.merchant.outbox.Outbox;
import dev.pixlab.merchant.payment.Payment;
import dev.pixlab.merchant.payment.PaymentRepository;
import dev.pixlab.merchant.psp.PspClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Devoluções. Pedido do recebedor: lança provisoriamente (débito em devoluções, crédito em psp:pix:liquidar) e
 * estorna se o PSP disser NAO_REALIZADO. MED: chega pronta, sem pedido, e é lançada ao chegar.
 *
 * <p>Invariante 3: com a linha do pagamento travada, Σ devoluções que não falharam ≤ valor do pagamento.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final ChargeRepository charges;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final Ledger ledger;
    private final Outbox outbox;
    private final PspClient psp;
    private final Clock clock;

    RefundService(ChargeRepository charges, PaymentRepository payments, RefundRepository refunds, Ledger ledger,
            Outbox outbox, PspClient psp, Clock clock) {
        this.charges = charges;
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.outbox = outbox;
        this.psp = psp;
        this.clock = clock;
    }

    @Transactional
    public Refund request(String txid, BigDecimal amount) {
        var charge = charges.findByTxid(txid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "cobrança não encontrada: " + txid));
        var paid = payments.findByChargeIdOrderByPaidAt(charge.getId());
        if (paid.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cobrança sem pagamento para devolver");
        }
        var payment = payments.lockByE2eId(paid.getFirst().getE2eId()).orElseThrow();
        requireWithinLimit(payment, amount);

        var refundId = "dev" + UUID.randomUUID().toString().replace("-", "").substring(0, 29);
        var refund = refunds.save(new Refund(payment.getId(), refundId, null, amount, RefundStatus.EM_PROCESSAMENTO,
                Refund.RequestedBy.MERCHANT, clock.instant()));
        ledger.post(ledgerRef(payment.getE2eId(), refundId),
                List.of(debit(Account.DEVOLUCOES, amount), credit(Account.PSP_PIX_LIQUIDAR, amount)));
        // Dentro da transação: se o PSP recusar, nem a devolução nem o lançamento ficam gravados.
        psp.solicitarDevolucao(payment.getE2eId(), refundId, new DevolucaoRequest(Valor.format(amount), null));
        log.info("Devolução {} de {} pedida para o Pix {}", refundId, amount, payment.getE2eId());
        return refund;
    }

    /**
     * Aplica uma atualização de devolução vinda do PSP. Devolve false se o pagamento ainda não chegou (PIX-OOO).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result apply(String e2eId, String refundId, String rtrId, BigDecimal amount, String natureza,
            RefundStatus status) {
        var payment = payments.lockByE2eId(e2eId).orElse(null);
        if (payment == null) {
            return Result.PAYMENT_NOT_FOUND;
        }
        var existing = refunds.findByPaymentIdAndRefundId(payment.getId(), refundId).orElse(null);
        if (existing == null) {
            if (!"MED".equals(natureza)) {
                return Result.UNKNOWN_REFUND;
            }
            return med(payment, refundId, rtrId, amount, status);
        }
        if (existing.getStatus() != RefundStatus.EM_PROCESSAMENTO || status == RefundStatus.EM_PROCESSAMENTO) {
            return Result.APPLIED;
        }
        existing.settle(status, rtrId, clock.instant());
        if (status == RefundStatus.NAO_REALIZADO) {
            // Estorno do lançamento provisório: o dinheiro não saiu.
            ledger.post(ledgerRef(e2eId, refundId), List.of(debit(Account.PSP_PIX_LIQUIDAR, existing.getAmount()),
                    credit(Account.DEVOLUCOES, existing.getAmount())));
            outbox.add("refund", refundId, "devolucao.nao_realizada", Map.of("endToEndId", e2eId, "id", refundId));
            log.warn("Devolução {} do Pix {} não realizada; lançamento estornado", refundId, e2eId);
        } else {
            outbox.add("refund", refundId, "devolucao.devolvida", Map.of("endToEndId", e2eId, "id", refundId));
        }
        updateCharge(payment);
        return Result.APPLIED;
    }

    /** O id da devolução só é único dentro do Pix (API Pix), então a referência no ledger leva o e2eId. */
    public static String ledgerRef(String e2eId, String refundId) {
        return e2eId + ":" + refundId;
    }

    public enum Result { APPLIED, PAYMENT_NOT_FOUND, UNKNOWN_REFUND, OVER_LIMIT }

    private Result med(Payment payment, String refundId, String rtrId, BigDecimal amount, RefundStatus status) {
        if (status != RefundStatus.DEVOLVIDO) {
            return Result.APPLIED;
        }
        if (refunds.committed(payment.getId()).add(amount).compareTo(payment.getAmount()) > 0) {
            return Result.OVER_LIMIT;
        }
        refunds.save(new Refund(payment.getId(), refundId, rtrId, amount, RefundStatus.DEVOLVIDO,
                Refund.RequestedBy.MED, clock.instant()));
        ledger.post(ledgerRef(payment.getE2eId(), refundId),
                List.of(debit(Account.DEVOLUCOES, amount), credit(Account.PSP_PIX_LIQUIDAR, amount)));
        outbox.add("refund", refundId, "devolucao.med", Map.of("endToEndId", payment.getE2eId(), "id", refundId,
                "valor", Valor.format(amount)));
        log.warn("MED {}: {} devolvido do Pix {} sem pedido do recebedor", refundId, amount, payment.getE2eId());
        updateCharge(payment);
        return Result.APPLIED;
    }

    private void updateCharge(Payment payment) {
        if (payment.getChargeId() == null) {
            return;
        }
        var charge = charges.findById(payment.getChargeId()).orElseThrow();
        var result = charge.apply(new ChargeEvent.RefundsSettled(refunds.settled(payment.getId())));
        if (result instanceof Transition.Rejected(var reason)) {
            log.info("Cobrança {} mantém {}: {}", charge.getTxid(), charge.getStatus(), reason);
        }
    }

    private void requireWithinLimit(Payment payment, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valor deve ser positivo");
        }
        var available = payment.getAmount().subtract(refunds.committed(payment.getId()));
        if (amount.compareTo(available) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "devolução de " + amount + " excede o disponível " + available);
        }
    }
}
