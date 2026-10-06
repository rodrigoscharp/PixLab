package dev.pixlab.psp.pix;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PixRecebidoRepository extends JpaRepository<PixRecebido, String> {

    List<PixRecebido> findByTxidOrderByHorario(String txid);
}
