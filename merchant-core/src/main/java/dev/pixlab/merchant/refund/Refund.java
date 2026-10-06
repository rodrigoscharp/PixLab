package dev.pixlab.merchant.refund;

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
public class Refund {

    public enum RequestedBy { MERCHANT, MED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long paymentId;
    private String refundId;
    private String rtrId;
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private RefundStatus status;

    @Enumerated(EnumType.STRING)
    private RequestedBy requestedBy;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;

    protected Refund() {}

    public Refund(Long paymentId, String refundId, String rtrId, BigDecimal amount, RefundStatus status,
            RequestedBy requestedBy, Instant now) {
        this.paymentId = paymentId;
        this.refundId = refundId;
        this.rtrId = rtrId;
        this.amount = amount;
        this.status = status;
        this.requestedBy = requestedBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void settle(RefundStatus to, String rtrId, Instant now) {
        this.status = to;
        if (rtrId != null) {
            this.rtrId = rtrId;
        }
        this.updatedAt = now;
    }

    public String getRefundId() {
        return refundId;
    }

    public String getRtrId() {
        return rtrId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public RequestedBy getRequestedBy() {
        return requestedBy;
    }
}
