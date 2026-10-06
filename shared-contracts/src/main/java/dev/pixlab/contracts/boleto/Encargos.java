package dev.pixlab.contracts.boleto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Valor devido de um boleto na data do pagamento: original + multa (uma vez) + juros pro rata dia (mês de 30 dias). */
public final class Encargos {

    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private static final BigDecimal TRINTA = BigDecimal.valueOf(30);

    private Encargos() {}

    public static BigDecimal devido(BigDecimal valor, LocalDate vencimento, BigDecimal multaPercentual,
            BigDecimal jurosMensalPercentual, LocalDate pagamento) {
        return valor.add(encargos(valor, vencimento, multaPercentual, jurosMensalPercentual, pagamento));
    }

    public static BigDecimal encargos(BigDecimal valor, LocalDate vencimento, BigDecimal multaPercentual,
            BigDecimal jurosMensalPercentual, LocalDate pagamento) {
        if (!pagamento.isAfter(vencimento)) {
            return BigDecimal.ZERO.setScale(2);
        }
        long dias = ChronoUnit.DAYS.between(vencimento, pagamento);
        var multa = valor.multiply(multaPercentual).divide(CEM, 2, RoundingMode.HALF_UP);
        var juros = valor.multiply(jurosMensalPercentual).multiply(BigDecimal.valueOf(dias))
                .divide(CEM.multiply(TRINTA), 2, RoundingMode.HALF_UP);
        return multa.add(juros);
    }
}
