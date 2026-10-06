package dev.pixlab.psp.chaos;

import java.time.Duration;
import java.util.Map;

/**
 * Perfil de caos: quais cenários estão ativos, com que probabilidade e parâmetros, e a seed que torna tudo
 * reproduzível. Chaves de {@code scenarios} são os códigos do catálogo, ex.: {@code PIX-DUP}.
 */
public record ChaosProfile(String name, long seed, Map<String, ScenarioConfig> scenarios) {

    public static final ChaosProfile NONE = new ChaosProfile("none", 0, Map.of());

    /**
     * @param probability    chance de o cenário acontecer para um dado Pix (0..1)
     * @param copies         entregas extras (PIX-DUP, PIX-DUP-CONC)
     * @param minDelay       atraso mínimo (PIX-DELAY)
     * @param maxDelay       atraso máximo (PIX-DELAY; também o atraso do crédito em PIX-OOO)
     * @param failedAttempts entregas com 2xx tratadas como timeout antes de aceitar (PIX-RETRY)
     */
    public record ScenarioConfig(double probability, Integer copies, Duration minDelay, Duration maxDelay,
            Integer failedAttempts) {}

    public ScenarioConfig config(ChaosScenario scenario) {
        return scenarios == null ? null : scenarios.get(scenario.code());
    }

    public void validate() {
        if (scenarios == null) {
            return;
        }
        scenarios.forEach((code, cfg) -> {
            ChaosScenario.fromCode(code);
            if (cfg.probability() < 0 || cfg.probability() > 1) {
                throw new IllegalArgumentException(code + ": probability deve estar entre 0 e 1");
            }
        });
    }
}
