package dev.pixlab.merchant.charge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pixlab.merchant.charge.ChargeEvent.PixReceived;
import dev.pixlab.merchant.charge.ChargeEvent.RefundsSettled;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ChargeStateMachineTest {

    private static final BigDecimal VALOR = new BigDecimal("150.00");

    @ParameterizedTest
    @CsvSource({
        "ATIVA,     150.00, CONCLUIDA",
        "ATIVA,     150.0,  CONCLUIDA",
        "ATIVA,     149.99, DIVERGENTE",
        "ATIVA,     150.01, DIVERGENTE",
        "EXPIRADA,  150.00, DIVERGENTE",
    })
    void pixRecebidoMoveCobranca(ChargeStatus from, BigDecimal paid, ChargeStatus to) {
        assertThat(ChargeStateMachine.transition(from, VALOR, new PixReceived(paid)))
                .isEqualTo(new Transition.Moved(to));
    }

    @ParameterizedTest
    @CsvSource({
        "CONCLUIDA,              50.00,  PARCIALMENTE_DEVOLVIDA",
        "CONCLUIDA,              150.00, DEVOLVIDA",
        "PARCIALMENTE_DEVOLVIDA, 150.00, DEVOLVIDA",
        "PARCIALMENTE_DEVOLVIDA, 0.00,   CONCLUIDA",
        "DEVOLVIDA,              149.99, PARCIALMENTE_DEVOLVIDA",
    })
    void devolucoesLiquidadasMovemCobranca(ChargeStatus from, BigDecimal refunded, ChargeStatus to) {
        assertThat(ChargeStateMachine.transition(from, VALOR, new RefundsSettled(refunded)))
                .isEqualTo(new Transition.Moved(to));
    }

    @ParameterizedTest
    @EnumSource(value = ChargeStatus.class, names = {"ATIVA", "DIVERGENTE", "EXPIRADA"})
    void devolucaoSemPagamentoConcluidoERejeitada(ChargeStatus from) {
        assertThat(ChargeStateMachine.transition(from, VALOR, new RefundsSettled(BigDecimal.ONE)))
                .isInstanceOf(Transition.Rejected.class);
    }

    @ParameterizedTest
    @EnumSource(value = ChargeStatus.class, names = {"ATIVA", "EXPIRADA"}, mode = EnumSource.Mode.EXCLUDE)
    void pixRecebidoEmEstadoFinalERejeitado(ChargeStatus from) {
        assertThat(ChargeStateMachine.transition(from, VALOR, new PixReceived(VALOR)))
                .isInstanceOf(Transition.Rejected.class);
    }
}
