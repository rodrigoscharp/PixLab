package dev.pixlab.merchant.charge;

import dev.pixlab.merchant.charge.ChargeEvent.PixReceived;
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
        };
    }
}
