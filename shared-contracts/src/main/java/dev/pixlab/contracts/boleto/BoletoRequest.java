package dev.pixlab.contracts.boleto;

/** Corpo de {@code POST /boletos}. */
public record BoletoRequest(String valor, String vencimento, String multaPercentual, String jurosMensalPercentual,
        String beneficiario) {}
