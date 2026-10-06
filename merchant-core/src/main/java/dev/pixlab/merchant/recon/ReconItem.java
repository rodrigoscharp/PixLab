package dev.pixlab.merchant.recon;

import dev.pixlab.contracts.pix.Pix;
import java.math.BigDecimal;

/**
 * @param pix          o Pix do extrato (nulo em FALTA_NO_PSP)
 * @param pspAmount    líquido no PSP: valor − devoluções liquidadas
 * @param ledgerAmount líquido em psp:pix:liquidar para o e2eId
 * @param adjustment   ajuste a lançar para o ledger bater com o PSP (VALOR_DIVERGENTE), ou nulo
 */
public record ReconItem(String key, ReconResult result, Pix pix, BigDecimal pspAmount, BigDecimal ledgerAmount,
        BigDecimal adjustment, String detail) {}
