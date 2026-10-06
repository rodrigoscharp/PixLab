package dev.pixlab.merchant.ledger;

import static dev.pixlab.merchant.ledger.Posting.credit;
import static dev.pixlab.merchant.ledger.Posting.debit;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerTest {

    private static final BigDecimal CEM = new BigDecimal("100.00");

    @Test
    void aceitaTransacaoBalanceada() {
        assertThatCode(() -> Ledger.requireBalanced(List.of(
                debit(Account.PSP_PIX_LIQUIDAR, CEM), credit(Account.RECEITA, CEM))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejeitaTransacaoDesbalanceada() {
        assertThatThrownBy(() -> Ledger.requireBalanced(List.of(
                debit(Account.PSP_PIX_LIQUIDAR, CEM), credit(Account.RECEITA, new BigDecimal("99.99")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desbalanceada");
    }

    @Test
    void rejeitaLancamentoUnico() {
        assertThatThrownBy(() -> Ledger.requireBalanced(List.of(debit(Account.PSP_PIX_LIQUIDAR, CEM))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejeitaLancamentoComDebitoECredito() {
        assertThatThrownBy(() -> Ledger.requireBalanced(List.of(
                new Posting(Account.PSP_PIX_LIQUIDAR, CEM, CEM), new Posting(Account.RECEITA, CEM, CEM))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejeitaLancamentoZerado() {
        assertThatThrownBy(() -> Ledger.requireBalanced(List.of(
                debit(Account.PSP_PIX_LIQUIDAR, BigDecimal.ZERO), credit(Account.RECEITA, BigDecimal.ZERO))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
