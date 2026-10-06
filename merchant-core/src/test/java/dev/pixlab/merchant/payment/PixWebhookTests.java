package dev.pixlab.merchant.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

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
        var e2eId = e2eId();

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

        assertThat(webhook(e2eId(), charge.getTxid(), "149.99")).hasStatusOk();

        awaitStatus(charge, ChargeStatus.DIVERGENTE);
        assertThat(mvc.get().uri("/ledger/balances")).hasStatusOk().bodyJson()
                .extractingPath("$.accounts['suspense:nao_identificado']").asString().startsWith("-");
    }

    @Test
    void mesmoWebhookEmSequenciaCreditaUmaVez() {
        var charge = novaCobranca("80.00");
        var e2eId = e2eId();

        for (int i = 0; i < 20; i++) {
            assertThat(webhook(e2eId, charge.getTxid(), "80.00")).hasStatusOk();
        }

        awaitStatus(charge, ChargeStatus.CONCLUIDA);
        assertThat(payments.findByChargeIdOrderByPaidAt(charge.getId())).hasSize(1);
        assertThat(jdbc.sql("select count(*) from webhook_inbox where event_key = ?").param(e2eId)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void pixParaTxidDesconhecidoNaoCredita() {
        var e2eId = e2eId();

        assertThat(webhook(e2eId, "desconhecido000000000000000001", "10.00")).hasStatusOk();

        await().untilAsserted(() -> assertThat(inboxStatus(e2eId)).isEqualTo("QUARANTINED"));
        assertThat(payments.existsByE2eId(e2eId)).isFalse();
    }

    @Test
    void payloadInvalidoERejeitadoAntesDaInbox() {
        var e2eId = "E123";

        assertThat(webhook(e2eId, "qualquer", "10.00")).hasStatus4xxClientError();
        assertThat(webhook(e2eId(), "qualquer", "10")).hasStatus4xxClientError();

        assertThat(jdbc.sql("select count(*) from webhook_inbox where event_key = ?").param(e2eId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void eventoProcessadoPublicaNaOutbox() {
        var charge = novaCobranca("42.00");

        assertThat(webhook(e2eId(), charge.getTxid(), "42.00")).hasStatusOk();

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

    private org.springframework.test.web.servlet.assertj.MvcTestResult webhook(String e2eId, String txid, String valor) {
        var body = """
                {"pix":[{"endToEndId":"%s","txid":"%s","valor":"%s","horario":"2026-10-06T15:30:12.358Z",
                 "infoPagador":"teste","devolucoes":[]}]}""".formatted(e2eId, txid, valor);
        return mvc.post().uri("/webhook/pix").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    static String e2eId() {
        return "E12345678202610061530" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }
}
