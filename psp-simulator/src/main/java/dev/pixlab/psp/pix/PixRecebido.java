package dev.pixlab.psp.pix;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.contracts.pix.Valor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Linha do extrato do PSP. */
@Entity
@Table(name = "pix")
public class PixRecebido {

    @Id
    @Column(name = "e2e_id")
    private String endToEndId;

    private String txid;
    private BigDecimal valor;
    private Instant horario;
    private String infoPagador;

    protected PixRecebido() {}

    public PixRecebido(String endToEndId, String txid, BigDecimal valor, Instant horario, String infoPagador) {
        this.endToEndId = endToEndId;
        this.txid = txid;
        this.valor = valor;
        this.horario = horario;
        this.infoPagador = infoPagador;
    }

    public Pix toContract() {
        return new Pix(endToEndId, txid, Valor.format(valor), horario.toString(), infoPagador, List.of());
    }

    public String getEndToEndId() {
        return endToEndId;
    }
}
