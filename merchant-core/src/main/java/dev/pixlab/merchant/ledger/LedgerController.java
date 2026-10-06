package dev.pixlab.merchant.ledger;

import dev.pixlab.contracts.pix.Valor;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LedgerController {

    record BalancesResponse(Map<String, String> accounts, String total) {}

    private final Ledger ledger;

    LedgerController(Ledger ledger) {
        this.ledger = ledger;
    }

    /** Saldo por conta; {@code total} é sempre 0.00 se o ledger está balanceado. */
    @GetMapping("/ledger/balances")
    BalancesResponse balances() {
        var accounts = new LinkedHashMap<String, String>();
        var total = BigDecimal.ZERO;
        for (var b : ledger.balances()) {
            accounts.put(b.account(), Valor.format(b.balance()));
            total = total.add(b.balance());
        }
        return new BalancesResponse(accounts, Valor.format(total));
    }
}
