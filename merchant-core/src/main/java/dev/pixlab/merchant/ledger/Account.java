package dev.pixlab.merchant.ledger;

/** Plano de contas mínimo (design doc, seção 6.3). */
public enum Account {
    PSP_PIX_LIQUIDAR("psp:pix:liquidar"),
    RECEITA("merchant:receita"),
    DEVOLUCOES("merchant:devolucoes"),
    SUSPENSE_NAO_IDENTIFICADO("suspense:nao_identificado"),
    CREDITO_A_DEVOLVER("merchant:credito_a_devolver");

    private final String code;

    Account(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
