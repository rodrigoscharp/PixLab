package dev.pixlab.merchant.payment;

import dev.pixlab.merchant.inbox.Inbox;
import dev.pixlab.merchant.psp.PspClient;
import dev.pixlab.merchant.support.Poller;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Webhook é otimização, não fonte da verdade (design doc, 6.4). Periodicamente lista o extrato do PSP numa
 * janela que se sobrepõe à anterior e injeta na inbox o que não foi visto. Mesmo caminho idempotente do webhook,
 * então reler o mesmo Pix é inofensivo; é assim que PIX-LOST se resolve.
 */
@Component
public class ConsultaAtiva {

    /**
     * @param interval de quanto em quanto tempo consultar
     * @param window   tamanho da janela consultada para trás a partir de agora
     */
    @ConfigurationProperties("pixlab.consulta-ativa")
    public record Props(boolean enabled, Duration interval, Duration window) {}

    public record Result(int found, int injected) {}

    private static final Logger log = LoggerFactory.getLogger(ConsultaAtiva.class);

    private final PspClient psp;
    private final Inbox inbox;
    private final JsonMapper json;
    private final Clock clock;
    private final Props props;

    ConsultaAtiva(PspClient psp, Inbox inbox, JsonMapper json, Clock clock, Props props) {
        this.psp = psp;
        this.inbox = inbox;
        this.json = json;
        this.clock = clock;
        this.props = props;
    }

    public Result run(Instant inicio, Instant fim) {
        var pix = psp.listarPix(inicio, fim);
        int injected = 0;
        for (var p : pix) {
            injected += PixEvents.offer(inbox, json, p, true);
        }
        if (injected > 0) {
            log.info("Consulta ativa [{} , {}): {} Pix no extrato, {} reinjetados", inicio, fim, pix.size(), injected);
        }
        return new Result(pix.size(), injected);
    }

    boolean tick() {
        var now = clock.instant();
        try {
            run(now.minus(props.window()), now.plusSeconds(1));
        } catch (RestClientException e) {
            log.warn("Consulta ativa falhou: {}", e.getMessage());
        }
        return false;
    }

    @Configuration(proxyBeanMethods = false)
    static class PollerConfig {

        @Bean
        Poller consultaAtivaPoller(ConsultaAtiva consulta, Props props) {
            return new Poller("consulta-ativa", props.enabled() ? 1 : 0, props.interval(), consulta::tick);
        }
    }
}
