package dev.pixlab.psp.cob;

import dev.pixlab.contracts.pix.CobStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
public class Cob {

    @Id
    private String txid;

    private String chave;
    private BigDecimal valor;
    private String solicitacaoPagador;

    @Enumerated(EnumType.STRING)
    private CobStatus status;

    private Instant criacao;
    private int expiracao;

    protected Cob() {}

    public Cob(String txid, String chave, BigDecimal valor, String solicitacaoPagador, Instant criacao, int expiracao) {
        this.txid = txid;
        this.chave = chave;
        this.valor = valor;
        this.solicitacaoPagador = solicitacaoPagador;
        this.status = CobStatus.ATIVA;
        this.criacao = criacao;
        this.expiracao = expiracao;
    }

    public boolean expiradaEm(Instant instante) {
        return !instante.isBefore(criacao.plusSeconds(expiracao));
    }

    public void concluir() {
        if (status != CobStatus.ATIVA) {
            throw new IllegalStateException("cobrança " + txid + " não está ATIVA: " + status);
        }
        status = CobStatus.CONCLUIDA;
    }

    public String getTxid() {
        return txid;
    }

    public String getChave() {
        return chave;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public String getSolicitacaoPagador() {
        return solicitacaoPagador;
    }

    public CobStatus getStatus() {
        return status;
    }

    public Instant getCriacao() {
        return criacao;
    }

    public int getExpiracao() {
        return expiracao;
    }
}
