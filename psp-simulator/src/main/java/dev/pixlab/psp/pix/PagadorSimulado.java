package dev.pixlab.psp.pix;

import dev.pixlab.contracts.pix.CobStatus;
import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.psp.chaos.ChaosEngine;
import dev.pixlab.psp.chaos.ChaosScenario;
import dev.pixlab.psp.cob.CobRepository;
import dev.pixlab.psp.support.SeedableRandom;
import dev.pixlab.psp.webhook.WebhookScheduler;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Simula o pagador quitando uma cobrança: liquida no extrato e agenda o webhook. */
@Service
public class PagadorSimulado {

    private static final Logger log = LoggerFactory.getLogger(PagadorSimulado.class);
    private static final String ALFANUMERICO = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final CobRepository cobs;
    private final PixRecebidoRepository extrato;
    private final EndToEndIdGenerator e2eIds;
    private final WebhookScheduler webhooks;
    private final ChaosEngine chaos;
    private final SeedableRandom random;
    private final Clock clock;

    PagadorSimulado(CobRepository cobs, PixRecebidoRepository extrato, EndToEndIdGenerator e2eIds,
            WebhookScheduler webhooks, ChaosEngine chaos, SeedableRandom random, Clock clock) {
        this.cobs = cobs;
        this.extrato = extrato;
        this.e2eIds = e2eIds;
        this.webhooks = webhooks;
        this.chaos = chaos;
        this.random = random;
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
        var pix = extrato.save(new PixRecebido(novoE2eId(agora), txid, cob.getChave(), cob.getValor(), agora, infoPagador))
                .toContract();
        webhooks.schedule(cob.getChave(), pix);

        if (chaos.happens(ChaosScenario.PIX_UNKNOWN, pix.endToEndId())) {
            var semCobranca = extrato.save(new PixRecebido(novoE2eId(agora), txidAleatorio(), cob.getChave(), cob.getValor(), agora,
                    "sem cobrança")).toContract();
            log.info("[PIX-UNKNOWN] {} para txid inexistente {}", semCobranca.endToEndId(), semCobranca.txid());
            webhooks.schedule(cob.getChave(), semCobranca);
        }
        return pix;
    }

    // A mesma seed reaplicada no mesmo minuto repete a sequência; o e2eId é único no SPI, então pula colisões.
    private String novoE2eId(Instant agora) {
        String e2eId;
        do {
            e2eId = e2eIds.gerar(agora);
        } while (extrato.existsById(e2eId));
        return e2eId;
    }

    private String txidAleatorio() {
        var txid = new StringBuilder("desconhecido");
        while (txid.length() < 32) {
            txid.append(ALFANUMERICO.charAt(random.nextInt(ALFANUMERICO.length())));
        }
        return txid.toString();
    }
}
