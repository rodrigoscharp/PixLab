package dev.pixlab.contracts.pix;

import java.util.List;

/** Pix recebido, como aparece no webhook e no extrato do PSP. */
public record Pix(
        String endToEndId,
        String txid,
        String valor,
        String horario,
        String infoPagador,
        List<Devolucao> devolucoes) {}
