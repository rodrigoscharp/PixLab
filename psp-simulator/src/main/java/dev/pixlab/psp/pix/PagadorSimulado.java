package dev.pixlab.psp.pix;

import dev.pixlab.contracts.pix.CobStatus;
import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.psp.cob.CobRepository;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Simula o pagador quitando uma cobrança: liquida no extrato e dispara o webhook. */
@Service
public class PagadorSimulado {

    private final CobRepository cobs;
    private final PixRecebidoRepository extrato;
    private final EndToEndIdGenerator e2eIds;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PagadorSimulado(CobRepository cobs, PixRecebidoRepository extrato, EndToEndIdGenerator e2eIds,
            ApplicationEventPublisher events, Clock clock) {
        this.cobs = cobs;
        this.extrato = extrato;
        this.e2eIds = e2eIds;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Pix pagar(String txid, String infoPagador) {
        var cob = cobs.findById(txid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "cobrança não encontrada: " + txid));
        var agora = clock.instant();
        if (cob.getStatus() != CobStatus.ATIVA) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cobrança não está ATIVA: " + cob.getStatus());
        }
        if (cob.expiradaEm(agora)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cobrança expirada");
        }
        cob.concluir();
        var pix = extrato.save(new PixRecebido(e2eIds.gerar(agora), txid, cob.getValor(), agora, infoPagador))
                .toContract();
        events.publishEvent(new PixRecebidoEvent(cob.getChave(), pix));
        return pix;
    }
}
