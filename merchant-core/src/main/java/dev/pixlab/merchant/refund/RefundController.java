package dev.pixlab.merchant.refund;

import dev.pixlab.contracts.pix.Valor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
class RefundController {

    record RefundRequest(String valor) {}

    record RefundResponse(String id, String valor, RefundStatus status) {}

    private final RefundService refunds;

    RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @PostMapping("/charges/{txid}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    RefundResponse request(@PathVariable String txid, @RequestBody RefundRequest request) {
        try {
            var refund = refunds.request(txid, Valor.parse(request.valor()));
            return new RefundResponse(refund.getRefundId(), Valor.format(refund.getAmount()), refund.getStatus());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PSP recusou ou não respondeu: " + e.getMessage());
        }
    }
}
