package dev.pixlab.e2e;

import org.junit.jupiter.api.Test;

/**
 * Execução parametrizável: {@code ./gradlew chaos --chaos-profile=mixed --seed=42 --payments=30}.
 * No build normal roda o perfil misto com a seed padrão.
 */
class ChaosRunE2ETest {

    @Test
    void perfilComSeed() throws Exception {
        var profile = System.getProperty("pixlab.chaos.profile", "mixed");
        var seed = Long.parseLong(System.getProperty("pixlab.chaos.seed", "42"));
        var payments = Integer.parseInt(System.getProperty("pixlab.chaos.payments", "20"));
        ChaosRun.run(profile, seed, payments);
    }
}
