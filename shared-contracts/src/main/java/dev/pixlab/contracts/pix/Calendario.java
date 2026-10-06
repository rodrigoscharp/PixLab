package dev.pixlab.contracts.pix;

/**
 * @param criacao   instante ISO-8601 de criação; preenchido apenas pelo PSP
 * @param expiracao segundos de validade a partir da criação
 */
public record Calendario(String criacao, Integer expiracao) {

    public static Calendario expiraEm(int segundos) {
        return new Calendario(null, segundos);
    }
}
