package dev.pixlab.merchant.inbox;

/** Resultado do processamento de um evento da inbox. */
public sealed interface Outcome {

    Outcome APPLIED = new Applied();

    record Applied() implements Outcome {}

    /** Ainda não dá para aplicar (ex.: devolução antes do crédito); tenta de novo com backoff. */
    record Retry(String reason) implements Outcome {}

    /** Nunca vai dar para aplicar sozinho; precisa de análise. */
    record Quarantine(String reason) implements Outcome {}
}
