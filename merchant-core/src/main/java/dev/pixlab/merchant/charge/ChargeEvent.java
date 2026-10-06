package dev.pixlab.merchant.charge;

import java.math.BigDecimal;

public sealed interface ChargeEvent {

    record PixReceived(BigDecimal amount) implements ChargeEvent {}

    /** Total já devolvido (devoluções liquidadas) mudou. */
    record RefundsSettled(BigDecimal refunded) implements ChargeEvent {}
}
