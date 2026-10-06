package dev.pixlab.contracts.pix;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** Valor monetário no formato da API Pix: string com duas casas decimais, ex.: {@code "150.00"}. */
public record Valor(String original) {

    private static final Pattern FORMATO = Pattern.compile("\\d{1,10}\\.\\d{2}");

    public static Valor of(BigDecimal amount) {
        return new Valor(format(amount));
    }

    public BigDecimal toBigDecimal() {
        return parse(original);
    }

    public static BigDecimal parse(String valor) {
        if (valor == null || !FORMATO.matcher(valor).matches()) {
            throw new IllegalArgumentException("valor fora do formato \\d{1,10}\\.\\d{2}: " + valor);
        }
        return new BigDecimal(valor);
    }

    public static String format(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
