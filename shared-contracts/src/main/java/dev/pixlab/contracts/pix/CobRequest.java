package dev.pixlab.contracts.pix;

/** Corpo de {@code PUT /cob/{txid}}. */
public record CobRequest(Calendario calendario, Valor valor, String chave, String solicitacaoPagador) {}
