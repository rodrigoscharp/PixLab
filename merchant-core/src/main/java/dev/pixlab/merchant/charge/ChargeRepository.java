package dev.pixlab.merchant.charge;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChargeRepository extends JpaRepository<Charge, Long> {

    Optional<Charge> findByTxid(String txid);

    Optional<Charge> findByNossoNumero(String nossoNumero);
}
