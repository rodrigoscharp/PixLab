package dev.pixlab.psp.devolucao;

import dev.pixlab.contracts.pix.Devolucao;
import dev.pixlab.contracts.pix.DevolucaoRequest;
import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.psp.chaos.ChaosEngine;
import dev.pixlab.psp.chaos.ChaosScenario;
import dev.pixlab.psp.chaos.DeliveryPlan;
import dev.pixlab.psp.pix.EndToEndIdGenerator;
import dev.pixlab.psp.pix.PixRecebido;
import dev.pixlab.psp.pix.PixRecebidoRepository;
import dev.pixlab.psp.support.Poller;
import dev.pixlab.psp.support.VirtualClock;
import dev.pixlab.psp.webhook.WebhookScheduler;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Devoluções de Pix: pedidas pelo recebedor (assíncronas, podem falhar) ou via MED (iniciadas pelo pagador).
 * A soma das devoluções que não falharam nunca passa do valor do Pix (invariante 3), garantida com lock na linha do Pix.
 */
@Service
public class DevolucaoService {

    private static final Logger log = LoggerFactory.getLogger(DevolucaoService.class);
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9]{1,35}");
    private static final Duration PROCESSAMENTO = Duration.ofSeconds(1);

    private final DevolucaoRepository devolucoes;
    private final PixRecebidoRepository extrato;
    private final EntityManager em;
    private final EndToEndIdGenerator ids;
    private final WebhookScheduler webhooks;
    private final ChaosEngine chaos;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final VirtualClock dispatchClock;

    DevolucaoService(DevolucaoRepository devolucoes, PixRecebidoRepository extrato, EntityManager em,
            EndToEndIdGenerator ids, WebhookScheduler webhooks, ChaosEngine chaos, TransactionTemplate tx,
            Clock clock, VirtualClock dispatchClock) {
        this.devolucoes = devolucoes;
        this.extrato = extrato;
        this.em = em;
        this.ids = ids;
        this.webhooks = webhooks;
        this.chaos = chaos;
        this.tx = tx;
        this.clock = clock;
        this.dispatchClock = dispatchClock;
    }

    /** {@code PUT} idempotente: repetir o mesmo id devolve a devolução já criada. */
    @Transactional
    public Devolucao solicitar(String e2eId, String id, DevolucaoRequest request) {
        if (!ID.matcher(id).matches()) {
            throw badRequest("id da devolução deve seguir [a-zA-Z0-9]{1,35}");
        }
        var pix = travarPix(e2eId);
        var existente = devolucoes.find(e2eId, id);
        if (existente.isPresent()) {
            return existente.get();
        }
        var valor = valor(request.valor());
        exigirSaldo(pix, valor);
        var devolucao = new Devolucao(id, ids.gerarRtrId(clock.instant()), Valor.format(valor), Devolucao.ORIGINAL,
                Devolucao.EM_PROCESSAMENTO, clock.instant().toString(), request.descricao());
        devolucoes.insert(e2eId, devolucao, clock.instant(), dispatchClock.instant().plus(PROCESSAMENTO));
        return devolucao;
    }

    /** MED: devolução que o recebedor não pediu, já liquidada, notificada por webhook. */
    @Transactional
    public Devolucao med(String e2eId, String valorTexto) {
        var pix = travarPix(e2eId);
        var valor = valorTexto == null ? pix.getValor().subtract(devolucoes.comprometido(e2eId)) : valor(valorTexto);
        exigirSaldo(pix, valor);
        var agora = clock.instant();
        var rtrId = ids.gerarRtrId(agora);
        var devolucao = new Devolucao("MED" + rtrId.substring(21), rtrId, Valor.format(valor), Devolucao.MED,
                Devolucao.DEVOLVIDO, agora.toString(), "MED: fraude ou falha operacional");
        devolucoes.insert(e2eId, devolucao, agora, null);
        log.info("[MED] {} devolveu {} do Pix {}", devolucao.id(), devolucao.valor(), e2eId);
        notificar(pix);
        return devolucao;
    }

    @Transactional(readOnly = true)
    public Devolucao consultar(String e2eId, String id) {
        return devolucoes.find(e2eId, id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "devolução não encontrada: " + id));
    }

    boolean resolverVencidas() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var pendentes = devolucoes.claimDue(dispatchClock.instant());
            for (var p : pendentes) {
                var falha = chaos.happens(ChaosScenario.PIX_REFUND_FAIL, p.rtrId());
                devolucoes.resolve(p.e2eId(), p.id(), falha ? Devolucao.NAO_REALIZADO : Devolucao.DEVOLVIDO,
                        falha ? "PIX-REFUND-FAIL: recusada pelo PSP do pagador" : null);
                if (falha) {
                    log.info("[PIX-REFUND-FAIL] devolução {} do Pix {} não realizada", p.id(), p.e2eId());
                }
                notificar(extrato.findById(p.e2eId()).orElseThrow());
            }
            return !pendentes.isEmpty();
        }));
    }

    private void notificar(PixRecebido pix) {
        var comDevolucoes = pix.toContract(devolucoes.byE2eId(pix.getEndToEndId()));
        webhooks.schedule(pix.getChave(), comDevolucoes, List.of(DeliveryPlan.now(comDevolucoes)));
    }

    private PixRecebido travarPix(String e2eId) {
        var pix = em.find(PixRecebido.class, e2eId, LockModeType.PESSIMISTIC_WRITE);
        if (pix == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "pix não encontrado: " + e2eId);
        }
        return pix;
    }

    private void exigirSaldo(PixRecebido pix, BigDecimal valor) {
        var disponivel = pix.getValor().subtract(devolucoes.comprometido(pix.getEndToEndId()));
        if (valor.signum() <= 0 || valor.compareTo(disponivel) > 0) {
            throw badRequest("valor da devolução " + Valor.format(valor) + " excede o disponível "
                    + Valor.format(disponivel));
        }
    }

    private static BigDecimal valor(String valor) {
        try {
            return Valor.parse(valor);
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    @Configuration(proxyBeanMethods = false)
    static class PollerConfig {

        @Bean
        Poller devolucaoPoller(DevolucaoService service) {
            return new Poller("devolucao", 1, Duration.ofMillis(100), service::resolverVencidas);
        }
    }
}
