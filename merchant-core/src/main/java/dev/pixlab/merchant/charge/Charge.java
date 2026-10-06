package dev.pixlab.merchant.charge;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Entity
public class Charge {

    public static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public enum Kind { PIX, BOLETO }

    @Enumerated(EnumType.STRING)
    private Kind kind = Kind.PIX;

    private String txid;
    private String nossoNumero;
    private LocalDate dueDate;
    private BigDecimal finePct;
    private BigDecimal interestPct;
    private String barcode;
    private String digitableLine;
    private BigDecimal amount;
    private String description;

    @Enumerated(EnumType.STRING)
    private ChargeStatus status;

    private Instant expiresAt;
    private Instant createdAt;

    @Version
    private long version;

    protected Charge() {}

    public Charge(String txid, BigDecimal amount, String description, Instant createdAt, Instant expiresAt) {
        this.txid = txid;
        this.amount = amount;
        this.description = description;
        this.status = ChargeStatus.ATIVA;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static Charge boleto(String txid, BigDecimal amount, String description, Instant createdAt,
            LocalDate dueDate, BigDecimal finePct, BigDecimal interestPct) {
        var charge = new Charge(txid, amount, description, createdAt, dueDate.plusDays(1).atStartOfDay(SAO_PAULO).toInstant());
        charge.kind = Kind.BOLETO;
        charge.dueDate = dueDate;
        charge.finePct = finePct;
        charge.interestPct = interestPct;
        return charge;
    }

    public void registered(String nossoNumero, String barcode, String digitableLine) {
        this.nossoNumero = nossoNumero;
        this.barcode = barcode;
        this.digitableLine = digitableLine;
    }

    public Transition apply(ChargeEvent event) {
        var result = ChargeStateMachine.transition(status, amount, event);
        if (result instanceof Transition.Moved(var to)) {
            status = to;
        }
        return result;
    }

    public Kind getKind() {
        return kind;
    }

    public String getNossoNumero() {
        return nossoNumero;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getFinePct() {
        return finePct;
    }

    public BigDecimal getInterestPct() {
        return interestPct;
    }

    public String getBarcode() {
        return barcode;
    }

    public String getDigitableLine() {
        return digitableLine;
    }

    public Long getId() {
        return id;
    }

    public String getTxid() {
        return txid;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getDescription() {
        return description;
    }

    public ChargeStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
