package dev.pixlab.psp.webhook;

import static java.time.ZoneOffset.UTC;

import dev.pixlab.contracts.pix.WebhookSignature;
import dev.pixlab.psp.support.Poller;
import dev.pixlab.psp.support.VirtualClock;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Envia as entregas vencidas (no relógio do dispatcher), em paralelo, e reagenda falhas com backoff
 * exponencial. Esgotadas as tentativas, a entrega vira FAILED e o Pix só é achado por consulta ativa.
 */
@Component
class WebhookDispatcher {

    record Due(long id, String e2eId, String url, String payload, boolean forged, int fakeFailures, int attempts) {}

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int MAX_ATTEMPTS = 8;
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(1);
    private static final Duration BACKOFF_MAX = Duration.ofMinutes(10);
    private static final Duration LEASE = Duration.ofSeconds(30);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final VirtualClock clock;
    private final RestClient http;
    private final String secret;

    WebhookDispatcher(JdbcClient jdbc, TransactionTemplate tx, VirtualClock dispatchClock, RestClient.Builder http,
            @Value("${pixlab.webhook.secret}") String secret) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = dispatchClock;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = http.requestFactory(factory).build();
        this.secret = secret;
    }

    boolean dispatchDue() {
        var due = claimDue();
        if (due.isEmpty()) {
            return false;
        }
        // Em paralelo e fora de transação: é o que faz cópias de PIX-DUP-CONC chegarem juntas no recebedor.
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            due.forEach(d -> pool.submit(() -> send(d)));
        }
        return true;
    }

    /**
     * Reserva as entregas vencidas marcando-as SENDING com um prazo (lease) e confirma na hora, para não
     * segurar lock durante o HTTP. Se o simulador cair no meio, a reserva vence e a entrega volta a ser enviada.
     */
    private List<Due> claimDue() {
        return tx.execute(status -> {
            var now = clock.instant();
            var due = jdbc.sql("""
                            select id, e2e_id, url, payload, forged, fake_failures, attempts from webhook_delivery
                            where status in ('PENDING', 'SENDING') and next_attempt_at <= :now
                            order by next_attempt_at, id limit 50
                            for update skip locked""")
                    .param("now", now.atOffset(UTC))
                    .query((rs, n) -> new Due(rs.getLong("id"), rs.getString("e2e_id"), rs.getString("url"),
                            rs.getString("payload"), rs.getBoolean("forged"), rs.getInt("fake_failures"),
                            rs.getInt("attempts")))
                    .list();
            if (!due.isEmpty()) {
                jdbc.sql("update webhook_delivery set status = 'SENDING', next_attempt_at = :lease where id in (:ids)")
                        .param("lease", now.plus(LEASE).atOffset(UTC))
                        .param("ids", due.stream().map(Due::id).toList())
                        .update();
            }
            return due;
        });
    }

    private void send(Due d) {
        var body = d.payload().getBytes(StandardCharsets.UTF_8);
        var signature = d.forged() ? "sha256=" + "0".repeat(64) : WebhookSignature.sign(secret, body);
        Integer statusCode = null;
        String error = null;
        try {
            statusCode = http.post().uri(d.url()).contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookSignature.HEADER, signature).body(body)
                    .exchange((req, res) -> res.getStatusCode().value());
        } catch (RuntimeException e) {
            error = e.toString();
        }
        boolean ok = statusCode != null && statusCode / 100 == 2;
        if (ok && d.attempts() < d.fakeFailures()) {
            ok = false;
            error = "PIX-RETRY: resposta descartada como timeout";
        }
        if (d.forged()) {
            finish(d, statusCode, "forjada", "DELIVERED");
        } else if (ok) {
            finish(d, statusCode, null, "DELIVERED");
        } else if (d.attempts() + 1 >= MAX_ATTEMPTS) {
            log.warn("Webhook do e2eId {} falhou {} vezes; desistindo", d.e2eId(), MAX_ATTEMPTS);
            finish(d, statusCode, error, "FAILED");
        } else {
            var delay = BACKOFF_BASE.multipliedBy(1L << d.attempts());
            delay = delay.compareTo(BACKOFF_MAX) > 0 ? BACKOFF_MAX : delay;
            jdbc.sql("""
                            update webhook_delivery set status = 'PENDING', attempts = attempts + 1,
                                next_attempt_at = :next, last_status = :status, last_error = :error
                            where id = :id""")
                    .param("next", clock.instant().plus(delay).atOffset(UTC))
                    .param("status", statusCode)
                    .param("error", error)
                    .param("id", d.id())
                    .update();
        }
    }

    private void finish(Due d, Integer statusCode, String error, String status) {
        jdbc.sql("""
                        update webhook_delivery set attempts = attempts + 1, status = :status, last_status = :code,
                            last_error = :error
                        where id = :id""")
                .param("status", status)
                .param("code", statusCode)
                .param("error", error)
                .param("id", d.id())
                .update();
        if ("DELIVERED".equals(status) && !d.forged()) {
            log.info("Webhook entregue: e2eId {} -> {} ({})", d.e2eId(), d.url(), statusCode);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PollerConfig {

        @Bean
        Poller webhookPoller(WebhookDispatcher dispatcher) {
            return new Poller("webhook", 1, Duration.ofMillis(100), dispatcher::dispatchDue);
        }
    }
}
