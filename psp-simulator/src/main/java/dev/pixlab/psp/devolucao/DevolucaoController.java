package dev.pixlab.psp.devolucao;

import dev.pixlab.contracts.pix.Devolucao;
import dev.pixlab.contracts.pix.DevolucaoRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DevolucaoController {

    record MedRequest(String valor) {}

    private final DevolucaoService service;

    DevolucaoController(DevolucaoService service) {
        this.service = service;
    }

    @PutMapping("/pix/{e2eId}/devolucao/{id}")
    @ResponseStatus(HttpStatus.CREATED)
    Devolucao solicitar(@PathVariable String e2eId, @PathVariable String id, @RequestBody DevolucaoRequest request) {
        return service.solicitar(e2eId, id, request);
    }

    @GetMapping("/pix/{e2eId}/devolucao/{id}")
    Devolucao consultar(@PathVariable String e2eId, @PathVariable String id) {
        return service.consultar(e2eId, id);
    }

    /** Laboratório: o pagador aciona o MED. Sem {@code valor}, devolve todo o saldo disponível. */
    @PostMapping("/sim/pix/{e2eId}/med")
    @ResponseStatus(HttpStatus.CREATED)
    Devolucao med(@PathVariable String e2eId, @RequestBody(required = false) MedRequest request) {
        return service.med(e2eId, request == null ? null : request.valor());
    }
}
