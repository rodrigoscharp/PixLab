package dev.pixlab.contracts.pix;

import java.util.List;

/** Resposta de {@code GET /pix?inicio&fim}: Pix recebidos na janela, paginados. */
public record PixListResponse(Parametros parametros, List<Pix> pix) {

    public record Parametros(String inicio, String fim, Paginacao paginacao) {}

    public record Paginacao(int paginaAtual, int itensPorPagina, int quantidadeDePaginas, long quantidadeTotalDeItens) {}
}
