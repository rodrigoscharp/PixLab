package dev.pixlab.psp.boleto;

import dev.pixlab.contracts.boleto.Boleto;
import dev.pixlab.contracts.boleto.BoletoRequest;
import dev.pixlab.contracts.boleto.Cnab240;
import java.time.LocalDate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BoletoController {

    record PagamentoRequest(String valor, String data) {}

    private final BoletoService boletos;

    BoletoController(BoletoService boletos) {
        this.boletos = boletos;
    }

    @PostMapping("/boletos")
    @ResponseStatus(HttpStatus.CREATED)
    Boleto emitir(@RequestBody BoletoRequest request) {
        return boletos.emitir(request);
    }

    @GetMapping("/boletos/{nossoNumero}")
    Boleto consultar(@PathVariable long nossoNumero) {
        return boletos.consultar(nossoNumero);
    }

    @PostMapping("/sim/boletos/{nossoNumero}/pagamento")
    @ResponseStatus(HttpStatus.CREATED)
    Cnab240.Ocorrencia pagar(@PathVariable long nossoNumero, @RequestBody(required = false) PagamentoRequest request) {
        return boletos.pagar(nossoNumero, request == null ? null : request.valor(), request == null ? null : request.data());
    }

    @PostMapping("/sim/boletos/{nossoNumero}/baixa")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void baixar(@PathVariable long nossoNumero) {
        boletos.baixar(nossoNumero);
    }

    /** Arquivo de retorno CNAB 240 do dia. {@code corromper=true} simula BOL-FILE-CORRUPT. */
    @GetMapping(value = "/cnab/retorno", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> retorno(@RequestParam LocalDate data, @RequestParam(defaultValue = "false") boolean corromper) {
        var nome = "RET_" + data.toString().replace("-", "") + (corromper ? "_corrompido" : "") + ".ret";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nome + "\"")
                .body(boletos.retorno(data, corromper));
    }
}
