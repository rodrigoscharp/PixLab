package dev.pixlab.contracts.pix;

/** Corpo de {@code PUT /pix/{e2eId}/devolucao/{id}}. */
public record DevolucaoRequest(String valor, String descricao) {}
