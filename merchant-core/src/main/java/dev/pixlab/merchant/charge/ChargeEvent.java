package dev.pixlab.merchant.charge;

import java.math.BigDecimal;

public sealed interface ChargeEvent {

    record PixReceived(BigDecimal amount) implements ChargeEvent {}
}
