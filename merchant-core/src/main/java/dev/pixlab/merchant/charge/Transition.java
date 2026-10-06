package dev.pixlab.merchant.charge;

public sealed interface Transition {

    record Moved(ChargeStatus to) implements Transition {}

    record Rejected(String reason) implements Transition {}
}
