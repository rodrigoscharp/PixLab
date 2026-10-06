package dev.pixlab.psp.pix;

import dev.pixlab.contracts.pix.Devolucao;
import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.contracts.pix.Valor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Linha do extrato do PSP. */
@Entity
@Table(name = "pix")
public class PixRecebido implements Persistable<String> {

    @Id
    @Column(name = "e2e_id")
    private String endToEndId;

    private String txid;
    private String chave;
    private BigDecimal valor;
    private Instant horario;
    private String infoPagador;

    protected PixRecebido() {}

    public PixRecebido(String endToEndId, String txid, String chave, BigDecimal valor, Instant horario,
            String infoPagador) {
        this.endToEndId = endToEndId;
        this.txid = txid;
        this.chave = chave;
        this.valor = valor;
        this.horario = horario;
        this.infoPagador = infoPagador;
    }

    public Pix toContract() {
        return toContract(List.of());
    }

    public Pix toContract(List<Devolucao> devolucoes) {
        return new Pix(endToEndId, txid, Valor.format(valor), horario.toString(), infoPagador, devolucoes);
    }

    public String getChave() {
        return chave;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public String getEndToEndId() {
        return endToEndId;
    }

    // Sempre insert: um e2eId repetido tem que falhar na PK, nunca sobrescrever um Pix do extrato.
    @Override
    public String getId() {
        return endToEndId;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
