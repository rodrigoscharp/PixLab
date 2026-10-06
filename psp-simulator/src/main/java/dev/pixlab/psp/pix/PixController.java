package dev.pixlab.psp.pix;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.contracts.pix.PixListResponse;
import dev.pixlab.contracts.pix.PixListResponse.Paginacao;
import dev.pixlab.contracts.pix.PixListResponse.Parametros;
import dev.pixlab.psp.devolucao.DevolucaoRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Extrato do PSP: {@code GET /pix?inicio&fim} (consulta ativa e conciliação) e {@code GET /pix/{e2eId}}. */
@RestController
class PixController {

    private static final int MAX_POR_PAGINA = 1000;

    private final PixRecebidoRepository extrato;
    private final DevolucaoRepository devolucoes;

    PixController(PixRecebidoRepository extrato, DevolucaoRepository devolucoes) {
        this.extrato = extrato;
        this.devolucoes = devolucoes;
    }

    @GetMapping("/pix")
    PixListResponse listar(@RequestParam Instant inicio, @RequestParam Instant fim,
            @RequestParam(name = "paginacao.paginaAtual", defaultValue = "0") int pagina,
            @RequestParam(name = "paginacao.itensPorPagina", defaultValue = "100") int itens) {
        if (!fim.isAfter(inicio)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fim deve ser depois de inicio");
        }
        if (pagina < 0 || itens < 1 || itens > MAX_POR_PAGINA) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paginação inválida");
        }
        var page = extrato.findByHorarioGreaterThanEqualAndHorarioLessThanOrderByHorarioAscEndToEndIdAsc(
                inicio, fim, PageRequest.of(pagina, itens));
        return new PixListResponse(
                new Parametros(inicio.toString(), fim.toString(),
                        new Paginacao(pagina, itens, page.getTotalPages(), page.getTotalElements())),
                comDevolucoes(page.getContent()));
    }

    private List<Pix> comDevolucoes(List<PixRecebido> pix) {
        var porE2eId = devolucoes.byE2eIds(pix.stream().map(PixRecebido::getEndToEndId).toList());
        return pix.stream().map(p -> p.toContract(porE2eId.getOrDefault(p.getEndToEndId(), List.of()))).toList();
    }

    @GetMapping("/pix/{e2eId}")
    Pix consultar(@PathVariable String e2eId) {
        return extrato.findById(e2eId).map(p -> p.toContract(devolucoes.byE2eId(e2eId)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "pix não encontrado: " + e2eId));
    }
}
