package dev.pixlab.merchant.ledger;

import java.math.BigDecimal;

public record Posting(Account account, BigDecimal debit, BigDecimal credit) {

    public static Posting debit(Account account, BigDecimal amount) {
        return new Posting(account, amount, BigDecimal.ZERO);
    }

    public static Posting credit(Account account, BigDecimal amount) {
        return new Posting(account, BigDecimal.ZERO, amount);
    }
}
