package dev.pixlab.psp.pix;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PixRecebidoRepository extends JpaRepository<PixRecebido, String> {

    List<PixRecebido> findByTxidOrderByHorario(String txid);

    Page<PixRecebido> findByHorarioGreaterThanEqualAndHorarioLessThanOrderByHorarioAscEndToEndIdAsc(
            Instant inicio, Instant fim, Pageable pageable);
}
