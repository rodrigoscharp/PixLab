package dev.pixlab.contracts.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EncargosTest {

    private static final BigDecimal VALOR = new BigDecimal("1000.00");
    private static final LocalDate VENCIMENTO = LocalDate.of(2026, 10, 10);
    private static final BigDecimal MULTA = new BigDecimal("2.00");
    private static final BigDecimal JUROS = new BigDecimal("1.00");

    @Test
    void semEncargosAteOVencimento() {
        assertThat(Encargos.devido(VALOR, VENCIMENTO, MULTA, JUROS, VENCIMENTO)).isEqualByComparingTo("1000.00");
        assertThat(Encargos.devido(VALOR, VENCIMENTO, MULTA, JUROS, VENCIMENTO.minusDays(3))).isEqualByComparingTo("1000.00");
    }

    @Test
    void multaUmaVezMaisJurosProRataDia() {
        // 2% de multa = 20,00; 1% a.m. por 15 dias = 5,00
        assertThat(Encargos.devido(VALOR, VENCIMENTO, MULTA, JUROS, VENCIMENTO.plusDays(15))).isEqualByComparingTo("1025.00");
        assertThat(Encargos.devido(VALOR, VENCIMENTO, MULTA, JUROS, VENCIMENTO.plusDays(1))).isEqualByComparingTo("1020.33");
    }
}
