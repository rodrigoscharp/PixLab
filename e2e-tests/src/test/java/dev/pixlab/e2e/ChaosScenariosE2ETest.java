package dev.pixlab.e2e;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** F3 — cada cenário de entrega do catálogo, isolado e sempre ativo, ponta a ponta. */
class ChaosScenariosE2ETest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dup", "dup-conc", "delay", "lost", "retry", "unknown", "bad-auth"})
    void cenarioMantemInvariantes(String profile) throws Exception {
        ChaosRun.run(profile, 1, 5);
    }
}
