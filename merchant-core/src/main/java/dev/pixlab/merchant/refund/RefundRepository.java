package dev.pixlab.merchant.refund;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByPaymentIdAndRefundId(Long paymentId, String refundId);

    List<Refund> findByPaymentIdOrderById(Long paymentId);

    /** Σ das devoluções que não falharam (pendentes contam: o dinheiro já está reservado). */
    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.paymentId = :paymentId and r.status <> 'NAO_REALIZADO'")
    BigDecimal committed(Long paymentId);

    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.paymentId = :paymentId and r.status = 'DEVOLVIDO'")
    BigDecimal settled(Long paymentId);
}
