package dev.pixlab.psp.pix;

import dev.pixlab.contracts.pix.Pix;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint do laboratório, fora da API Pix: aciona o pagador simulado. */
@RestController
class PagadorSimuladoController {

    record PagamentoRequest(String infoPagador) {}

    private final PagadorSimulado pagador;

    PagadorSimuladoController(PagadorSimulado pagador) {
        this.pagador = pagador;
    }

    @PostMapping("/sim/cob/{txid}/pagamento")
    @ResponseStatus(HttpStatus.CREATED)
    Pix pagar(@PathVariable String txid, @RequestBody(required = false) PagamentoRequest request,
            @RequestParam(defaultValue = "false") boolean duplicar) {
        return pagador.pagar(txid, request == null ? null : request.infoPagador(), duplicar);
    }
}
