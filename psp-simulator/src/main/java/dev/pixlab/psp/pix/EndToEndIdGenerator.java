package dev.pixlab.psp.pix;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gera e2eIds no formato do SPI: {@code E} + ISPB (8) + {@code yyyyMMddHHmm} (UTC) + 11 alfanuméricos = 32 caracteres.
 */
@Component
public class EndToEndIdGenerator {

    private static final DateTimeFormatter MINUTO = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final String ALFABETO = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SUFIXO = 11;

    private final String ispb;
    private final RandomGenerator random;

    EndToEndIdGenerator(@Value("${pixlab.psp.ispb}") String ispb, RandomGenerator random) {
        if (!ispb.matches("\\d{8}")) {
            throw new IllegalArgumentException("ISPB deve ter 8 dígitos: " + ispb);
        }
        this.ispb = ispb;
        this.random = random;
    }

    public String gerar(Instant instante) {
        var e2eId = new StringBuilder(32).append('E').append(ispb).append(MINUTO.format(instante));
        for (int i = 0; i < SUFIXO; i++) {
            e2eId.append(ALFABETO.charAt(random.nextInt(ALFABETO.length())));
        }
        return e2eId.toString();
    }
}
