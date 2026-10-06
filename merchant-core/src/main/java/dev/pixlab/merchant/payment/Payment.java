package dev.pixlab.merchant.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long chargeId;

    @Column(name = "e2e_id")
    private String e2eId;

    private String boletoRef;
    private BigDecimal amount;
    private Instant paidAt;
    private Instant createdAt;

    protected Payment() {}

    public Payment(Long chargeId, String e2eId, BigDecimal amount, Instant paidAt, Instant createdAt) {
        this.chargeId = chargeId;
        this.e2eId = e2eId;
        this.amount = amount;
        this.paidAt = paidAt;
        this.createdAt = createdAt;
    }

    public static Payment boleto(Long chargeId, String boletoRef, BigDecimal amount, Instant paidAt, Instant createdAt) {
        var payment = new Payment(chargeId, null, amount, paidAt, createdAt);
        payment.boletoRef = boletoRef;
        return payment;
    }

    public String getBoletoRef() {
        return boletoRef;
    }

    public Long getId() {
        return id;
    }

    public Long getChargeId() {
        return chargeId;
    }

    public String getE2eId() {
        return e2eId;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
