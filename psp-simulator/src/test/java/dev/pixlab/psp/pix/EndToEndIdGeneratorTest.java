package dev.pixlab.psp.pix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class EndToEndIdGeneratorTest {

    private static final Instant INSTANTE = Instant.parse("2026-10-06T15:30:12.358Z");

    @Test
    void segueFormatoDoSpi() {
        var e2eId = new EndToEndIdGenerator("12345678", new SplittableRandom(42)).gerar(INSTANTE);

        assertThat(e2eId).hasSize(32).matches("E12345678202610061530[a-z0-9]{11}");
    }

    @Test
    void mesmaSeedGeraMesmoId() {
        var a = new EndToEndIdGenerator("12345678", new SplittableRandom(7)).gerar(INSTANTE);
        var b = new EndToEndIdGenerator("12345678", new SplittableRandom(7)).gerar(INSTANTE);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void rejeitaIspbInvalido() {
        assertThatThrownBy(() -> new EndToEndIdGenerator("1234", new SplittableRandom()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
