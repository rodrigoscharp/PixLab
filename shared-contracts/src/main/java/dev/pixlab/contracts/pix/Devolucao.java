package dev.pixlab.contracts.pix;

/** Devolução de um Pix recebido. Usada a partir da F4. */
public record Devolucao(String id, String rtrId, String valor, String status) {}
