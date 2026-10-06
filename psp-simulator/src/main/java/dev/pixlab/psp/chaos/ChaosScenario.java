package dev.pixlab.psp.chaos;

import java.util.Arrays;

/** Cenários de caos do catálogo (design doc, seção 5) que o simulador sabe injetar. */
public enum ChaosScenario {
    PIX_DUP("PIX-DUP"),
    PIX_DUP_CONC("PIX-DUP-CONC"),
    PIX_DELAY("PIX-DELAY"),
    PIX_LOST("PIX-LOST"),
    PIX_RETRY("PIX-RETRY"),
    PIX_UNKNOWN("PIX-UNKNOWN"),
    PIX_AMOUNT("PIX-AMOUNT"),
    PIX_BAD_AUTH("PIX-BAD-AUTH"),
    PIX_OOO("PIX-OOO"),
    PIX_REFUND_FAIL("PIX-REFUND-FAIL");

    private final String code;

    ChaosScenario(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ChaosScenario fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("cenário desconhecido: " + code));
    }
}
