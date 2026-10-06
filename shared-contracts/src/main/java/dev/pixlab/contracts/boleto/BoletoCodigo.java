package dev.pixlab.contracts.boleto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Código de barras (44 dígitos) e linha digitável (47 dígitos) de boleto, padrão Febraban.
 *
 * <pre>
 * código de barras: banco(3) moeda(1) DV(1) fator(4) valor(10) campo livre(25)
 * linha digitável:  campo1 = banco moeda livre[0,5) DV10 | campo2 = livre[5,15) DV10 |
 *                   campo3 = livre[15,25) DV10 | campo4 = DV geral | campo5 = fator valor
 * </pre>
 */
public final class BoletoCodigo {

    public static final String MOEDA_REAL = "9";

    private static final LocalDate BASE_ORIGINAL = LocalDate.of(1997, 10, 7);
    /** Em 22/02/2025 o fator chegou a 9999+1 e recomeçou em 1000 (Febraban). */
    private static final LocalDate BASE_NOVA = LocalDate.of(2025, 2, 22);

    private BoletoCodigo() {}

    public static String codigoBarras(String banco, LocalDate vencimento, BigDecimal valor, String campoLivre) {
        requireDigits(banco, 3, "banco");
        requireDigits(campoLivre, 25, "campo livre");
        var cents = valor.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
        var valorCampo = String.format("%010d", cents);
        if (valorCampo.length() > 10) {
            throw new IllegalArgumentException("valor acima do máximo do boleto: " + valor);
        }
        var semDv = banco + MOEDA_REAL + fatorVencimento(vencimento) + valorCampo + campoLivre;
        return semDv.substring(0, 4) + dvCodigoBarras(semDv) + semDv.substring(4);
    }

    public static String linhaDigitavel(String codigoBarras) {
        requireDigits(codigoBarras, 44, "código de barras");
        var livre = codigoBarras.substring(19, 44);
        var campo1 = codigoBarras.substring(0, 4) + livre.substring(0, 5);
        var campo2 = livre.substring(5, 15);
        var campo3 = livre.substring(15, 25);
        return campo1 + mod10(campo1) + campo2 + mod10(campo2) + campo3 + mod10(campo3)
                + codigoBarras.charAt(4) + codigoBarras.substring(5, 19);
    }

    /** Confere o DV geral (módulo 11) de um código de barras. */
    public static boolean codigoBarrasValido(String codigoBarras) {
        if (codigoBarras == null || !codigoBarras.matches("\\d{44}")) {
            return false;
        }
        var semDv = codigoBarras.substring(0, 4) + codigoBarras.substring(5);
        return dvCodigoBarras(semDv) == codigoBarras.charAt(4) - '0';
    }

    /** Confere os três DVs (módulo 10) dos campos da linha digitável e se ela corresponde ao código de barras. */
    public static boolean linhaDigitavelValida(String linha, String codigoBarras) {
        return linha != null && linha.matches("\\d{47}") && linha.equals(linhaDigitavel(codigoBarras));
    }

    public static String fatorVencimento(LocalDate vencimento) {
        long fator = vencimento.isBefore(BASE_NOVA)
                ? ChronoUnit.DAYS.between(BASE_ORIGINAL, vencimento)
                : 1000 + ChronoUnit.DAYS.between(BASE_NOVA, vencimento);
        if (fator < 1000 || fator > 9999) {
            throw new IllegalArgumentException("vencimento fora da faixa do fator: " + vencimento);
        }
        return String.format("%04d", fator);
    }

    /** Módulo 10: pesos 2,1 da direita para a esquerda; produtos ≥ 10 somam os dígitos. */
    public static int mod10(String digits) {
        int sum = 0;
        int weight = 2;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int product = (digits.charAt(i) - '0') * weight;
            sum += product / 10 + product % 10;
            weight = weight == 2 ? 1 : 2;
        }
        return (10 - sum % 10) % 10;
    }

    /** Módulo 11 do DV geral: pesos 2..9 da direita para a esquerda; resultado 0, 10 ou 11 vira 1. */
    static int dvCodigoBarras(String semDv) {
        int sum = 0;
        int weight = 2;
        for (int i = semDv.length() - 1; i >= 0; i--) {
            sum += (semDv.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int dv = 11 - sum % 11;
        return dv == 0 || dv == 10 || dv == 11 ? 1 : dv;
    }

    private static void requireDigits(String value, int length, String field) {
        if (value == null || value.length() != length || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(field + " deve ter " + length + " dígitos: " + value);
        }
    }
}
