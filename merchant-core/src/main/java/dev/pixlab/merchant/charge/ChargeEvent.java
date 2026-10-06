package dev.pixlab.merchant.charge;

import java.math.BigDecimal;

public sealed interface ChargeEvent {

    record PixReceived(BigDecimal amount) implements ChargeEvent {}

    /** Boleto liquidado: {@code expected} é o devido na data do pagamento (original + multa + juros). */
    record BoletoPaid(BigDecimal paid, BigDecimal expected) implements ChargeEvent {}

    /** Boleto baixado pelo banco antes de ser pago. */
    record BoletoCancelled() implements ChargeEvent {}

    /** Total já devolvido (devoluções liquidadas) mudou. */
    record RefundsSettled(BigDecimal refunded) implements ChargeEvent {}
}
