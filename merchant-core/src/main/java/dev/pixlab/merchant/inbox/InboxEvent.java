package dev.pixlab.merchant.inbox;

/** Evento reivindicado da inbox. {@code traceParent} é o trace de quem o entregou, para o processamento continuar nele. */
public record InboxEvent(long id, String source, String eventKey, String payload, int attempts, String traceParent) {}
