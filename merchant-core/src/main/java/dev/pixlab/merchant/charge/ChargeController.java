package dev.pixlab.merchant.charge;

import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.merchant.payment.Payment;
import dev.pixlab.merchant.payment.PaymentRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/charges")
class ChargeController {

    record CreateChargeRequest(String valor, String descricao) {}

    record ChargeResponse(String txid, String valor, String descricao, ChargeStatus status, String expiraEm,
            List<String> endToEndIds) {}

    private final ChargeService charges;
    private final PaymentRepository payments;

    ChargeController(ChargeService charges, PaymentRepository payments) {
        this.charges = charges;
        this.payments = payments;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ChargeResponse create(@RequestBody CreateChargeRequest request) {
        try {
            return toResponse(charges.create(Valor.parse(request.valor()), request.descricao()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PSP recusou ou não respondeu: " + e.getMessage());
        }
    }

    @GetMapping("/{txid}")
    ChargeResponse get(@PathVariable String txid) {
        return toResponse(charges.get(txid));
    }

    private ChargeResponse toResponse(Charge charge) {
        var e2eIds = payments.findByChargeIdOrderByPaidAt(charge.getId()).stream().map(Payment::getE2eId).toList();
        return new ChargeResponse(charge.getTxid(), Valor.format(charge.getAmount()), charge.getDescription(),
                charge.getStatus(), charge.getExpiresAt().toString(), e2eIds);
    }
}
