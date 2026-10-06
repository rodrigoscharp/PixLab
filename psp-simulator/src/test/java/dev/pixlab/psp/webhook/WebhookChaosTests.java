package dev.pixlab.psp.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import dev.pixlab.contracts.pix.Calendario;
import dev.pixlab.contracts.pix.CobRequest;
import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.contracts.pix.WebhookSignature;
import dev.pixlab.psp.TestcontainersConfiguration;
import dev.pixlab.psp.chaos.ChaosEngine;
import dev.pixlab.psp.chaos.ChaosProfile;
import dev.pixlab.psp.chaos.ChaosProfile.ScenarioConfig;
import dev.pixlab.psp.cob.CobService;
import dev.pixlab.psp.pix.PagadorSimulado;
import dev.pixlab.psp.support.VirtualClock;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Entregas reais de webhook, sob caos, para um servidor HTTP de teste que faz o papel do recebedor. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WebhookChaosTests {

    record Received(String body, boolean validSignature) {}

    @Autowired
    CobService cobs;

    @Autowired
    PagadorSimulado pagador;

    @Autowired
    ChaosEngine chaos;

    @Autowired
    VirtualClock clock;

    @Autowired
    WebhookRepository webhooks;

    HttpServer server;
    List<Received> received = new CopyOnWriteArrayList<>();
    volatile int responseStatus = 200;
    String chave;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/webhook/pix", exchange -> {
            var body = exchange.getRequestBody().readAllBytes();
            var valid = WebhookSignature.verify("pixlab-dev-secret", body,
                    exchange.getRequestHeaders().getFirst(WebhookSignature.HEADER));
            received.add(new Received(new String(body), valid));
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
        chave = "chave-" + UUID.randomUUID();
        webhooks.save(new Webhook(chave, "http://localhost:" + server.getAddress().getPort() + "/webhook",
                Instant.now()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        chaos.reset();
    }

    @Test
    void semCaosEntregaUmaVezAssinado() {
        pagar();

        await().untilAsserted(() -> assertThat(received).hasSize(1));
        assertThat(received.getFirst().validSignature()).isTrue();
    }

    @Test
    void pixDupEntregaVariasVezes() {
        chaos.apply(only("PIX-DUP", new ScenarioConfig(1, 3, null, null, null)));

        var pix = pagar();

        await().untilAsserted(() -> assertThat(received).hasSize(4));
        assertThat(received).allSatisfy(r -> assertThat(r.body()).contains(pix));
    }

    @Test
    void pixDelaySoEntregaQuandoORelogioAnda() throws InterruptedException {
        chaos.apply(only("PIX-DELAY", new ScenarioConfig(1, null, Duration.ofHours(2), Duration.ofHours(2), null)));

        pagar();
        Thread.sleep(500);
        assertThat(received).isEmpty();

        clock.advance(Duration.ofHours(2));
        await().untilAsserted(() -> assertThat(received).hasSize(1));
    }

    @Test
    void pixLostNaoEntrega() throws InterruptedException {
        chaos.apply(only("PIX-LOST", new ScenarioConfig(1, null, null, null, null)));

        pagar();
        Thread.sleep(500);

        assertThat(received).isEmpty();
    }

    @Test
    void pixRetryReenviaComBackoffAteSerAceito() {
        chaos.apply(only("PIX-RETRY", new ScenarioConfig(1, null, null, null, 2)));

        pagar();
        await().untilAsserted(() -> assertThat(received).hasSize(1));
        clock.advance(Duration.ofSeconds(1));
        await().untilAsserted(() -> assertThat(received).hasSize(2));
        clock.advance(Duration.ofSeconds(2));
        await().untilAsserted(() -> assertThat(received).hasSize(3));
        clock.advance(Duration.ofMinutes(10));
        assertThat(received).hasSize(3);
    }

    @Test
    void recebedorFora5xxTambemReenvia() {
        responseStatus = 503;
        pagar();
        await().untilAsserted(() -> assertThat(received).hasSize(1));

        responseStatus = 200;
        clock.advance(Duration.ofSeconds(1));
        await().untilAsserted(() -> assertThat(received).hasSize(2));
    }

    @Test
    void pixBadAuthMandaUmaEntregaForjada() {
        chaos.apply(only("PIX-BAD-AUTH", new ScenarioConfig(1, null, null, null, null)));

        pagar();

        await().untilAsserted(() -> assertThat(received).hasSize(2));
        assertThat(received).extracting(Received::validSignature).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void pixUnknownGeraPixSemCobranca() {
        chaos.apply(only("PIX-UNKNOWN", new ScenarioConfig(1, null, null, null, null)));

        pagar();

        await().untilAsserted(() -> assertThat(received).hasSize(2));
        assertThat(received).anySatisfy(r -> assertThat(r.body()).contains("\"txid\":\"desconhecido"));
    }

    private String pagar() {
        var txid = UUID.randomUUID().toString().replace("-", "");
        cobs.criar(txid, new CobRequest(Calendario.expiraEm(3600), Valor.of(new BigDecimal("150.00")), chave, null));
        return pagador.pagar(txid, null).endToEndId();
    }

    private static ChaosProfile only(String code, ScenarioConfig cfg) {
        return new ChaosProfile(code, 1, Map.of(code, cfg));
    }
}
