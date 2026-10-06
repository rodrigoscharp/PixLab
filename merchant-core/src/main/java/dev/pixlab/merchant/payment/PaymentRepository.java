package dev.pixlab.merchant.payment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    boolean existsByE2eId(String e2eId);

    List<Payment> findByChargeIdOrderByPaidAt(Long chargeId);
}
