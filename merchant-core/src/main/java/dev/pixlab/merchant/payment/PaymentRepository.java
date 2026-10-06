package dev.pixlab.merchant.payment;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    boolean existsByE2eId(String e2eId);

    List<Payment> findByChargeIdOrderByPaidAt(Long chargeId);

    /** Lock na linha do pagamento: serializa devoluções concorrentes do mesmo Pix (invariante 3). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.e2eId = :e2eId")
    Optional<Payment> lockByE2eId(String e2eId);
}
