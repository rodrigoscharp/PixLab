package dev.pixlab.merchant.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pixlab.contracts.pix.WebhookSignature;
import dev.pixlab.merchant.TestData;
import dev.pixlab.merchant.TestcontainersConfiguration;
import dev.pixlab.merchant.charge.Charge;
import dev.pixlab.merchant.charge.ChargeRepository;
import dev.pixlab.merchant.charge.ChargeStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PixWebhookTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ChargeRepository charges;

    @Autowired
    PaymentRepository payments;

    @Autowired
    JdbcClient jdbc;

    @Test
    void pixComValorCertoConcluiCobrancaELancaReceita() {
        var charge = novaCobranca("150.00");
        var e2eId = TestData.e2eId();

        assertThat(webhook(e2eId, charge.getTxid(), "150.00")).hasStatusOk();

        awaitStatus(charge, ChargeStatus.CONCLUIDA);
        assertThat(payments.findByChargeIdOrderByPaidAt(charge.getId())).extracting(Payment::getE2eId)
                .containsExactly(e2eId);
        assertThat(mvc.get().uri("/ledger/balances")).hasStatusOk().bodyJson()
                .extractingPath("$.total").isEqualTo("0.00");
    }

    @Test
    void pixComValorDiferenteDeixaCobrancaDivergente() {
        var charge = novaCobranca("150.00");

        assertThat(webhook(TestData.e2eId(), charge.getTxid(), "149.99")).hasStatusOk();

        awaitStatus(charge, ChargeStatus.DIVERGENTE);
        assertThat(mvc.get().uri("/ledger/balances")).hasStatusOk().bodyJson()
                .extractingPath("$.accounts['suspense:nao_identificado']").asString().startsWith("-");
    }

    @Test
    void mesmoWebhookEmSequenciaCreditaUmaVez() {
        var charge = novaCobranca("80.00");
        var e2eId = TestData.e2eId();

        for (int i = 0; i < 20; i++) {
            assertThat(webhook(e2eId, charge.getTxid(), "80.00")).hasStatusOk();
        }

        awaitStatus(charge, ChargeStatus.CONCLUIDA);
        assertThat(payments.findByChargeIdOrderByPaidAt(charge.getId())).hasSize(1);
        assertThat(jdbc.sql("select count(*) from webhook_inbox where event_key = ?").param(e2eId)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void pixParaTxidDesconhecidoVaiParaSuspense() {
        var e2eId = TestData.e2eId();

        assertThat(webhook(e2eId, "desconhecido000000000000000001", "10.00")).hasStatusOk();

        await().untilAsserted(() -> assertThat(inboxStatus(e2eId)).isEqualTo("PROCESSED"));
        assertThat(payments.existsByE2eId(e2eId)).isTrue();
        assertThat(jdbc.sql("select account from ledger_entry where ref = ? and credit > 0").param(e2eId)
                .query(String.class).single()).isEqualTo("suspense:nao_identificado");
    }

    @Test
    void webhookSemAssinaturaValidaERejeitadoAntesDaInbox() {
        var charge = novaCobranca("10.00");
        var e2eId = TestData.e2eId();
        var body = TestData.webhookBody(e2eId, charge.getTxid(), "10.00");

        assertThat(post(body, false)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/webhook/pix").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(WebhookSignature.HEADER, TestData.sign(body.replace("10.00", "99.00")))).hasStatus(HttpStatus.UNAUTHORIZED);

        assertThat(jdbc.sql("select count(*) from webhook_inbox where event_key = ?").param(e2eId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void payloadInvalidoERejeitadoAntesDaInbox() {
        var e2eId = "E123";

        assertThat(webhook(e2eId, "qualquer", "10.00")).hasStatus4xxClientError();
        assertThat(webhook(TestData.e2eId(), "qualquer", "10")).hasStatus4xxClientError();

        assertThat(jdbc.sql("select count(*) from webhook_inbox where event_key = ?").param(e2eId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void eventoProcessadoPublicaNaOutbox() {
        var charge = novaCobranca("42.00");

        assertThat(webhook(TestData.e2eId(), charge.getTxid(), "42.00")).hasStatusOk();

        await().untilAsserted(() -> assertThat(jdbc.sql(
                        "select count(*) from outbox where aggregate_id = ? and published_at is not null")
                .param(charge.getTxid()).query(Long.class).single()).isEqualTo(1));
    }

    private String inboxStatus(String e2eId) {
        return jdbc.sql("select status from webhook_inbox where event_key = ?").param(e2eId).query(String.class).single();
    }

    private void awaitStatus(Charge charge, ChargeStatus expected) {
        await().untilAsserted(() -> assertThat(status(charge)).isEqualTo(expected));
    }

    private Charge novaCobranca(String valor) {
        var now = Instant.now();
        var txid = UUID.randomUUID().toString().replace("-", "");
        return charges.save(new Charge(txid, new BigDecimal(valor), "teste", now, now.plus(1, ChronoUnit.HOURS)));
    }

    private ChargeStatus status(Charge charge) {
        return charges.findByTxid(charge.getTxid()).orElseThrow().getStatus();
    }

    private MvcTestResult webhook(String e2eId, String txid, String valor) {
        return post(TestData.webhookBody(e2eId, txid, valor), true);
    }

    private MvcTestResult post(String body, boolean signed) {
        var request = mvc.post().uri("/webhook/pix").contentType(MediaType.APPLICATION_JSON).content(body);
        if (signed) {
            request = request.header(WebhookSignature.HEADER, TestData.sign(body));
        }
        return request.exchange();
    }



}
