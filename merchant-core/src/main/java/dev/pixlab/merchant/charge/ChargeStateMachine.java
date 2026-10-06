package dev.pixlab.merchant.charge;

import dev.pixlab.merchant.charge.ChargeEvent.BoletoCancelled;
import dev.pixlab.merchant.charge.ChargeEvent.BoletoPaid;
import dev.pixlab.merchant.charge.ChargeEvent.PixReceived;
import dev.pixlab.merchant.charge.ChargeEvent.RefundsSettled;
import dev.pixlab.merchant.charge.Transition.Moved;
import dev.pixlab.merchant.charge.Transition.Rejected;
import java.math.BigDecimal;

/** Função pura de transição da cobrança (design doc, seção 6.2). Não toca em banco nem relógio. */
public final class ChargeStateMachine {

    private ChargeStateMachine() {}

    public static Transition transition(ChargeStatus state, BigDecimal chargeAmount, ChargeEvent event) {
        return switch (event) {
            case PixReceived pix -> switch (state) {
                case ATIVA -> pix.amount().compareTo(chargeAmount) == 0
                        ? new Moved(ChargeStatus.CONCLUIDA)
                        : new Moved(ChargeStatus.DIVERGENTE);
                case EXPIRADA -> new Moved(ChargeStatus.DIVERGENTE);
                default -> new Rejected("Pix recebido para cobrança em " + state);
            };
            case BoletoPaid boleto -> switch (state) {
                // Pago a maior também conclui; o excedente vira crédito a devolver (ADR-0008).
                case ATIVA -> boleto.paid().compareTo(boleto.expected()) >= 0
                        ? new Moved(ChargeStatus.CONCLUIDA)
                        : new Moved(ChargeStatus.DIVERGENTE);
                case EXPIRADA -> new Moved(ChargeStatus.DIVERGENTE);
                default -> new Rejected("boleto pago de novo com cobrança em " + state);
            };
            case BoletoCancelled cancelled -> state == ChargeStatus.ATIVA
                    ? new Moved(ChargeStatus.EXPIRADA)
                    : new Rejected("baixa de boleto com cobrança em " + state);
            case RefundsSettled refunds -> switch (state) {
                case CONCLUIDA, PARCIALMENTE_DEVOLVIDA, DEVOLVIDA -> {
                    var cmp = refunds.refunded().compareTo(chargeAmount);
                    if (cmp > 0) {
                        yield new Rejected("devolvido " + refunds.refunded() + " excede " + chargeAmount);
                    }
                    yield new Moved(refunds.refunded().signum() == 0 ? ChargeStatus.CONCLUIDA
                            : cmp == 0 ? ChargeStatus.DEVOLVIDA : ChargeStatus.PARCIALMENTE_DEVOLVIDA);
                }
                default -> new Rejected("devolução para cobrança em " + state);
            };
        };
    }
}
