package dev.pixlab.contracts.boleto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BoletoCodigoTest {

    // Boleto real do Banco do Brasil usado como exemplo público em validadores de linha digitável.
    private static final String CODIGO_BB = "00193373700000001000500940144816060680935031";
    private static final String LINHA_BB = "00190500954014481606906809350314337370000000100";

    @Test
    void validaBoletoRealDoBancoDoBrasil() {
        assertThat(BoletoCodigo.codigoBarrasValido(CODIGO_BB)).isTrue();
        assertThat(BoletoCodigo.linhaDigitavel(CODIGO_BB)).isEqualTo(LINHA_BB);
    }

    @Test
    void reconstroiOBoletoRealAPartirDosCampos() {
        var codigo = BoletoCodigo.codigoBarras("001", LocalDate.of(2007, 12, 31), new BigDecimal("1.00"),
                CODIGO_BB.substring(19));

        assertThat(codigo).isEqualTo(CODIGO_BB);
    }

    @Test
    void detectaDigitoAlterado() {
        var adulterado = CODIGO_BB.substring(0, 30) + (CODIGO_BB.charAt(30) == '9' ? '0' : '9') + CODIGO_BB.substring(31);

        assertThat(BoletoCodigo.codigoBarrasValido(adulterado)).isFalse();
    }

    @Test
    void fatorDeVencimentoReiniciaEm22DeFevereiroDe2025() {
        assertThat(BoletoCodigo.fatorVencimento(LocalDate.of(2007, 12, 31))).isEqualTo("3737");
        assertThat(BoletoCodigo.fatorVencimento(LocalDate.of(2025, 2, 21))).isEqualTo("9999");
        assertThat(BoletoCodigo.fatorVencimento(LocalDate.of(2025, 2, 22))).isEqualTo("1000");
        assertThat(BoletoCodigo.fatorVencimento(LocalDate.of(2026, 10, 6))).isEqualTo("1591");
    }

    @Test
    void mod10DosCamposDaLinha() {
        assertThat(BoletoCodigo.mod10("001905009")).isEqualTo(5);
        assertThat(BoletoCodigo.mod10("4014481606")).isEqualTo(9);
        assertThat(BoletoCodigo.mod10("0680935031")).isEqualTo(4);
    }

    @Test
    void rejeitaCampoLivreDeTamanhoErrado() {
        assertThatThrownBy(() -> BoletoCodigo.codigoBarras("001", LocalDate.of(2026, 10, 6), BigDecimal.TEN, "123"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
