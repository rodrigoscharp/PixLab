package dev.pixlab.merchant.inbox;

/** Aplica eventos de uma {@code source} da inbox. Roda dentro da transação do processador. */
public interface InboxEventHandler {

    String source();

    Outcome handle(InboxEvent event);
}
