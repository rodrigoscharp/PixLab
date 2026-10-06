package dev.pixlab.psp.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.psp.chaos.ChaosProfile.ScenarioConfig;
import dev.pixlab.psp.support.SeedableRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChaosEngineTest {

    private static final Pix PIX = new Pix("E12345678202610061530abcdefghijk", "txid", "150.00",
            "2026-10-06T15:30:12.358Z", null, List.of());

    @Test
    void semPerfilEntregaUmaVezNaHora() {
        assertThat(engine(ChaosProfile.NONE).plan(PIX)).containsExactly(DeliveryPlan.now(PIX));
    }

    @Test
    void mesmaSeedMesmoPlano() {
        var profile = mixed(7);
        var pixes = IntStream.range(0, 50).mapToObj(i -> new Pix("E12345678202610061530" + "%011d".formatted(i),
                "t", "10.00", "2026-10-06T15:30:12.358Z", null, List.of())).toList();

        var a = pixes.stream().map(engine(profile)::plan).toList();
        var b = pixes.stream().map(engine(profile)::plan).toList();

        assertThat(a).isEqualTo(b);
    }

    @Test
    void seedsDiferentesDaoPlanosDiferentes() {
        var pixes = IntStream.range(0, 50).mapToObj(i -> new Pix("E12345678202610061530" + "%011d".formatted(i),
                "t", "10.00", "2026-10-06T15:30:12.358Z", null, List.of())).toList();

        assertThat(pixes.stream().map(engine(mixed(1))::plan).toList())
                .isNotEqualTo(pixes.stream().map(engine(mixed(2))::plan).toList());
    }

    @Test
    void lostNaoEntrega() {
        assertThat(engine(only("PIX-LOST", new ScenarioConfig(1, null, null, null, null))).plan(PIX)).isEmpty();
    }

    @Test
    void dupEntregaCopias() {
        assertThat(engine(only("PIX-DUP", new ScenarioConfig(1, 3, null, null, null))).plan(PIX)).hasSize(4);
    }

    @Test
    void delayFicaNoIntervalo() {
        var plan = engine(only("PIX-DELAY", new ScenarioConfig(1, null, Duration.ofMinutes(10), Duration.ofHours(2), null)))
                .plan(PIX);

        assertThat(plan).singleElement().satisfies(p ->
                assertThat(p.delay()).isBetween(Duration.ofMinutes(10), Duration.ofHours(2)));
    }

    @Test
    void amountAlteraSoOPayload() {
        var plan = engine(only("PIX-AMOUNT", new ScenarioConfig(1, null, null, null, null))).plan(PIX);

        assertThat(plan).singleElement().satisfies(p -> {
            assertThat(p.payload().valor()).isNotEqualTo("150.00");
            assertThat(p.payload().endToEndId()).isEqualTo(PIX.endToEndId());
        });
    }

    @Test
    void badAuthAcrescentaEntregaForjada() {
        var plan = engine(only("PIX-BAD-AUTH", new ScenarioConfig(1, null, null, null, null))).plan(PIX);

        assertThat(plan).extracting(DeliveryPlan::forged).containsExactly(true, false);
    }

    @Test
    void rejeitaCenarioDesconhecidoEProbabilidadeInvalida() {
        assertThatThrownBy(() -> engine(only("PIX-XYZ", new ScenarioConfig(1, null, null, null, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine(only("PIX-DUP", new ScenarioConfig(1.5, null, null, null, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ChaosEngine engine(ChaosProfile profile) {
        var engine = new ChaosEngine(new SeedableRandom(0));
        engine.apply(profile);
        return engine;
    }

    private static ChaosProfile only(String code, ScenarioConfig cfg) {
        return new ChaosProfile(code, 1, Map.of(code, cfg));
    }

    private static ChaosProfile mixed(long seed) {
        return new ChaosProfile("mixed", seed, Map.of(
                "PIX-DUP", new ScenarioConfig(0.3, 3, null, null, null),
                "PIX-DELAY", new ScenarioConfig(0.4, null, Duration.ofSeconds(1), Duration.ofHours(2), null),
                "PIX-LOST", new ScenarioConfig(0.15, null, null, null, null),
                "PIX-AMOUNT", new ScenarioConfig(0.1, null, null, null, null)));
    }
}
