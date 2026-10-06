package dev.pixlab.contracts.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pixlab.contracts.boleto.Cnab240.Ocorrencia;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class Cnab240Test {

    private static final Ocorrencia LIQUIDACAO = new Ocorrencia("12345678901", Cnab240.LIQUIDACAO, "oc-1",
            LocalDate.of(2026, 10, 10), new BigDecimal("1000.00"), new BigDecimal("25.00"), new BigDecimal("1025.00"),
            LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26));
    private static final Ocorrencia ENTRADA = new Ocorrencia("12345678902", Cnab240.ENTRADA_CONFIRMADA, "oc-2",
            LocalDate.of(2026, 11, 1), new BigDecimal("50.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
            LocalDate.of(2026, 10, 25), null);

    @Test
    void escreveLinhasDe240ELeDeVolta() {
        var file = write(List.of(LIQUIDACAO, ENTRADA));

        assertThat(file.split("\r\n")).hasSize(8).allSatisfy(l -> assertThat(l).hasSize(240));
        var parsed = Cnab240.parse(file);
        assertThat(parsed.erros()).isEmpty();
        assertThat(parsed.ocorrencias()).containsExactly(LIQUIDACAO, ENTRADA);
    }

    @Test
    void posicoesFebraban() {
        var lines = write(List.of(LIQUIDACAO)).split("\r\n");
        var t = lines[2];
        var u = lines[3];

        assertThat(t.substring(7, 8)).isEqualTo("3");
        assertThat(t.charAt(13)).isEqualTo('T');
        assertThat(t.substring(15, 17)).isEqualTo("06");
        assertThat(t.substring(37, 57)).isEqualTo("00000000012345678901");
        assertThat(t.substring(73, 81)).isEqualTo("10102026");
        assertThat(t.substring(81, 96)).isEqualTo("000000000100000");
        assertThat(u.charAt(13)).isEqualTo('U');
        assertThat(u.substring(77, 92)).isEqualTo("000000000102500");
        assertThat(u.substring(137, 145)).isEqualTo("25102026");
        assertThat(lines[0].charAt(142)).isEqualTo('2');
    }

    @Test
    void linhaCorrompidaDescartaSoOParAfetado() {
        var lines = write(List.of(LIQUIDACAO, ENTRADA)).split("\r\n");
        // Valor pago do primeiro U com letras no meio (BOL-FILE-CORRUPT).
        lines[3] = lines[3].substring(0, 80) + "XX" + lines[3].substring(82);

        var parsed = Cnab240.parse(String.join("\r\n", lines));

        assertThat(parsed.ocorrencias()).containsExactly(ENTRADA);
        assertThat(parsed.erros()).singleElement().satisfies(e -> {
            assertThat(e.linha()).isEqualTo(4);
            assertThat(e.motivo()).contains("valor pago");
        });
    }

    @Test
    void linhaTruncadaViraErro() {
        var lines = write(List.of(LIQUIDACAO)).split("\r\n");
        lines[2] = lines[2].substring(0, 200);

        var parsed = Cnab240.parse(String.join("\r\n", lines));

        assertThat(parsed.ocorrencias()).isEmpty();
        assertThat(parsed.erros()).extracting(Cnab240.LineError::linha).contains(3);
    }

    private static String write(List<Ocorrencia> ocorrencias) {
        return Cnab240.write("999", 1, LocalDate.of(2026, 10, 25), LocalTime.of(23, 0), ocorrencias);
    }
}
