package dev.pixlab.contracts.pix;

import java.util.List;

/** Corpo de {@code POST {webhookUrl}/pix}: lote de Pix recebidos. */
public record PixWebhook(List<Pix> pix) {}
