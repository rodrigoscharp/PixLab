package dev.pixlab.psp.boleto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pixlab.contracts.boleto.Boleto;
import dev.pixlab.contracts.boleto.BoletoCodigo;
import dev.pixlab.contracts.boleto.BoletoRequest;
import dev.pixlab.contracts.boleto.Cnab240;
import dev.pixlab.psp.TestcontainersConfiguration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BoletoServiceTests {

    private static final LocalDate VENCIMENTO = LocalDate.of(2027, 5, 10);

    @Autowired
    BoletoService boletos;

    @Test
    void emiteBoletoComCodigoDeBarrasELinhaDigitavelValidos() {
        var boleto = emitir("1234.56");

        assertThat(boleto.codigoBarras()).startsWith("9999").hasSize(44);
        assertThat(BoletoCodigo.codigoBarrasValido(boleto.codigoBarras())).isTrue();
        assertThat(BoletoCodigo.linhaDigitavelValida(boleto.linhaDigitavel(), boleto.codigoBarras())).isTrue();
        assertThat(boleto.codigoBarras().substring(9, 19)).isEqualTo("0000123456");
        assertThat(boleto.codigoBarras().substring(19, 30)).isEqualTo("%011d".formatted(Long.parseLong(boleto.nossoNumero())));
    }

    @Test
    void pagamentoAtrasadoCobraMultaEJurosEApareceNoRetornoDoDia() {
        var boleto = emitir("1000.00");
        var data = VENCIMENTO.plusDays(15);

        var pago = boletos.pagar(Long.parseLong(boleto.nossoNumero()), null, data.toString());

        assertThat(pago.valorPago()).isEqualByComparingTo("1025.00");
        assertThat(pago.jurosMulta()).isEqualByComparingTo("25.00");
        var parsed = Cnab240.parse(boletos.retorno(data, false));
        assertThat(parsed.erros()).isEmpty();
        assertThat(parsed.ocorrencias()).anySatisfy(o -> {
            assertThat(o.nossoNumero()).isEqualTo(boleto.nossoNumero());
            assertThat(o.codigo()).isEqualTo(Cnab240.LIQUIDACAO);
            assertThat(o.valorPago()).isEqualByComparingTo("1025.00");
            assertThat(o.dataCredito()).isEqualTo(data.plusDays(1));
        });
    }

    @Test
    void retornoCorrompidoTemUmaLinhaInvalida() {
        var boleto = emitir("10.00");
        var data = LocalDate.of(2027, 5, 1);
        boletos.pagar(Long.parseLong(boleto.nossoNumero()), "10.00", data.toString());

        var parsed = Cnab240.parse(boletos.retorno(data, true));

        assertThat(parsed.erros()).hasSize(1);
    }

    @Test
    void boletoBaixadoNaoPodeSerPago() {
        var nn = Long.parseLong(emitir("10.00").nossoNumero());
        boletos.baixar(nn);

        assertThatThrownBy(() -> boletos.pagar(nn, null, null)).isInstanceOf(ResponseStatusException.class);
    }

    private Boleto emitir(String valor) {
        return boletos.emitir(new BoletoRequest(valor, VENCIMENTO.toString(), "2.00", "1.00", "recebedor"));
    }
}
