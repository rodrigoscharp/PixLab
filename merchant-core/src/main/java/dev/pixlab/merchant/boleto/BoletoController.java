package dev.pixlab.merchant.boleto;

import dev.pixlab.contracts.boleto.BoletoCodigo;
import dev.pixlab.contracts.boleto.BoletoRequest;
import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.merchant.charge.Charge;
import dev.pixlab.merchant.charge.ChargeRepository;
import dev.pixlab.merchant.charge.ChargeStatus;
import dev.pixlab.merchant.psp.PspClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
class BoletoController {

    record CreateBoletoRequest(String valor, String vencimento, String multaPercentual, String jurosMensalPercentual,
            String descricao) {}

    record BoletoChargeResponse(String txid, String nossoNumero, String valor, String vencimento, ChargeStatus status,
            String codigoBarras, String linhaDigitavel) {}

    private final ChargeRepository charges;
    private final PspClient psp;
    private final ReturnFiles returnFiles;
    private final Clock clock;

    BoletoController(ChargeRepository charges, PspClient psp, ReturnFiles returnFiles, Clock clock) {
        this.charges = charges;
        this.psp = psp;
        this.returnFiles = returnFiles;
        this.clock = clock;
    }

    /** Emite o boleto no banco e grava a cobrança; o código de barras devolvido é conferido antes de aceitar. */
    @PostMapping("/charges/boleto")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    BoletoChargeResponse create(@RequestBody CreateBoletoRequest request) {
        BigDecimal valor;
        LocalDate vencimento;
        try {
            valor = Valor.parse(request.valor());
            vencimento = LocalDate.parse(request.vencimento());
        } catch (IllegalArgumentException | DateTimeParseException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valor ou vencimento inválido");
        }
        var multa = percent(request.multaPercentual());
        var juros = percent(request.jurosMensalPercentual());
        var charge = charges.save(Charge.boleto(UUID.randomUUID().toString().replace("-", ""), valor,
                request.descricao(), clock.instant(), vencimento, multa, juros));
        try {
            var boleto = psp.emitirBoleto(new BoletoRequest(Valor.format(valor), vencimento.toString(),
                    multa.toPlainString(), juros.toPlainString(), "recebedor@pixlab.dev"));
            if (!BoletoCodigo.codigoBarrasValido(boleto.codigoBarras())
                    || !BoletoCodigo.linhaDigitavelValida(boleto.linhaDigitavel(), boleto.codigoBarras())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "banco devolveu código de barras inválido");
            }
            charge.registered(boleto.nossoNumero(), boleto.codigoBarras(), boleto.linhaDigitavel());
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "banco recusou ou não respondeu: " + e.getMessage());
        }
        return toResponse(charge);
    }

    @GetMapping("/charges/boleto/{nossoNumero}")
    BoletoChargeResponse get(@PathVariable String nossoNumero) {
        return toResponse(charges.findByNossoNumero(nossoNumero)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    /** Recebe o arquivo de retorno CNAB 240 como texto. Reenviar o mesmo arquivo não tem efeito. */
    @PostMapping(value = "/cnab/retornos", consumes = MediaType.TEXT_PLAIN_VALUE)
    ReturnFiles.Ingested upload(@RequestParam(defaultValue = "retorno.ret") String nome, @RequestBody String content) {
        return returnFiles.ingest(nome, content);
    }

    @GetMapping("/cnab/retornos/{id}")
    Map<String, Object> reconcile(@PathVariable long id) {
        return returnFiles.reconcile(id);
    }

    private static BoletoChargeResponse toResponse(Charge c) {
        return new BoletoChargeResponse(c.getTxid(), c.getNossoNumero(), Valor.format(c.getAmount()),
                c.getDueDate().toString(), c.getStatus(), c.getBarcode(), c.getDigitableLine());
    }

    private static BigDecimal percent(String value) {
        try {
            return value == null ? BigDecimal.ZERO.setScale(2) : new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "percentual inválido: " + value);
        }
    }
}
