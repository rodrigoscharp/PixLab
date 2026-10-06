package dev.pixlab.merchant.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pixlab.merchant.TestcontainersConfiguration;
import dev.pixlab.merchant.charge.Charge;
import dev.pixlab.merchant.charge.ChargeRepository;
import dev.pixlab.merchant.charge.ChargeStatus;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Cenário PIX-DUP-CONC: 1.000 entregas simultâneas do mesmo webhook geram exatamente 1 crédito. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentDuplicateWebhookTests {

    private static final int DELIVERIES = 1_000;

    @LocalServerPort
    int port;

    @Autowired
    ChargeRepository charges;

    @Autowired
    JdbcClient jdbc;

    @Test
    void milEntregasConcorrentesGeramUmCredito() throws Exception {
        var now = Instant.now();
        var txid = UUID.randomUUID().toString().replace("-", "");
        charges.save(new Charge(txid, new BigDecimal("150.00"), "corrida", now, now.plus(1, ChronoUnit.HOURS)));
        var e2eId = PixWebhookTests.e2eId();
        var body = """
                {"pix":[{"endToEndId":"%s","txid":"%s","valor":"150.00","horario":"2026-10-06T15:30:12.358Z",
                 "devolucoes":[]}]}""".formatted(e2eId, txid);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhook/pix"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        var start = new CountDownLatch(1);
        var responses = new ArrayList<Future<Integer>>();
        try (var http = HttpClient.newHttpClient(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < DELIVERIES; i++) {
                responses.add(pool.submit(() -> {
                    start.await();
                    return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                }));
            }
            start.countDown();
            for (var r : responses) {
                assertThat(r.get()).isEqualTo(200);
            }
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(charges.findByTxid(txid).orElseThrow().getStatus()).isEqualTo(ChargeStatus.CONCLUIDA));
        assertThat(count("select count(*) from webhook_inbox where event_key = ?", e2eId)).isEqualTo(1);
        assertThat(count("select count(*) from payment where e2e_id = ?", e2eId)).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entry where ref = ?", e2eId)).isEqualTo(2);
    }

    private long count(String sql, String param) {
        return jdbc.sql(sql).param(param).query(Long.class).single();
    }
}
