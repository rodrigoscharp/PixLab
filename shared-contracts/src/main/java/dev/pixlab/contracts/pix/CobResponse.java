package dev.pixlab.contracts.pix;

import java.util.List;

/** Resposta de {@code PUT /cob/{txid}} e {@code GET /cob/{txid}}. */
public record CobResponse(
        String txid,
        CobStatus status,
        Calendario calendario,
        Valor valor,
        String chave,
        String solicitacaoPagador,
        List<Pix> pix) {}
