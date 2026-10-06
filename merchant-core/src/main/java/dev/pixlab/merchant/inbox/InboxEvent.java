package dev.pixlab.merchant.inbox;

public record InboxEvent(long id, String source, String eventKey, String payload, int attempts) {}
