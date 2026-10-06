package dev.pixlab.contracts.pix;

/**
 * Devolução de um Pix recebido.
 *
 * @param id       identificador definido por quem pediu (recebedor) ou pelo PSP (MED)
 * @param rtrId    identificador da devolução no SPI: {@code D} + ISPB + {@code yyyyMMddHHmm} + 11 alfanuméricos
 * @param natureza {@code ORIGINAL} (pedida pelo recebedor) ou {@code MED} (iniciada pelo lado pagador)
 * @param status   {@code EM_PROCESSAMENTO}, {@code DEVOLVIDO} ou {@code NAO_REALIZADO}
 */
public record Devolucao(String id, String rtrId, String valor, String natureza, String status, String solicitacao,
        String motivo) {

    public static final String EM_PROCESSAMENTO = "EM_PROCESSAMENTO";
    public static final String DEVOLVIDO = "DEVOLVIDO";
    public static final String NAO_REALIZADO = "NAO_REALIZADO";
    public static final String ORIGINAL = "ORIGINAL";
    public static final String MED = "MED";
}
