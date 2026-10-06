package dev.pixlab.merchant.ledger;

import java.math.BigDecimal;

/** Saldo de uma conta: Σ débito − Σ crédito. */
public record AccountBalance(String account, BigDecimal debit, BigDecimal credit) {

    public BigDecimal balance() {
        return debit.subtract(credit);
    }
}
