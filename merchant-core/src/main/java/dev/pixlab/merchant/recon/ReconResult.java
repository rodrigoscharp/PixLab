package dev.pixlab.merchant.recon;

/** Classificação de um item na conciliação (design doc, seção 7). */
public enum ReconResult {
    /** Presente e igual no PSP e no ledger. */
    OK,
    /** PSP tem, ledger não: reinjeta na inbox (auto-reparo). */
    FALTA_NO_LEDGER,
    /** Ledger tem, PSP não: possível crédito fantasma, alerta crítico. */
    FALTA_NO_PSP,
    /** Valores diferentes: ajusta o ledger para o valor do PSP e abre caso. */
    VALOR_DIVERGENTE,
    /** Pago sem cobrança conhecida: fica em suspense. */
    SEM_COBRANCA,
    /** Segundo pagamento da mesma cobrança: crédito a devolver. */
    DUPLICADO
}
