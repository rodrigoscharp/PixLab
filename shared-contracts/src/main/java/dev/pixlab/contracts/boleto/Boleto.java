package dev.pixlab.contracts.boleto;

/** Boleto registrado no banco (simulador). Datas em ISO-8601 ({@code yyyy-MM-dd}); valores como {@code "150.00"}. */
public record Boleto(String nossoNumero, String valor, String vencimento, String multaPercentual,
        String jurosMensalPercentual, String codigoBarras, String linhaDigitavel, String status) {}
