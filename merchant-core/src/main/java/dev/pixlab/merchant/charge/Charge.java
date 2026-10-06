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

@Entity
public class Charge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String txid;
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

    public Transition apply(ChargeEvent event) {
        var result = ChargeStateMachine.transition(status, amount, event);
        if (result instanceof Transition.Moved(var to)) {
            status = to;
        }
        return result;
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
