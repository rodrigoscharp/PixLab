package dev.pixlab.merchant.payment;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void pixComValorCertoConcluiCobrancaELancaReceita() {
        var charge = novaCobranca("150.00");
        var e2eId = e2eId();

        assertThat(webhook(e2eId, charge.getTxid(), "150.00")).hasStatusOk();

        assertThat(status(charge)).isEqualTo(ChargeStatus.CONCLUIDA);
        assertThat(payments.findByChargeIdOrderByPaidAt(charge.getId())).extracting(Payment::getE2eId)
                .containsExactly(e2eId);
        assertThat(mvc.get().uri("/ledger/balances")).hasStatusOk().bodyJson()
                .extractingPath("$.total").isEqualTo("0.00");
    }

    @Test
    void pixComValorDiferenteDeixaCobrancaDivergente() {
        var charge = novaCobranca("150.00");

        assertThat(webhook(e2eId(), charge.getTxid(), "149.99")).hasStatusOk();

        assertThat(status(charge)).isEqualTo(ChargeStatus.DIVERGENTE);
        assertThat(mvc.get().uri("/ledger/balances")).hasStatusOk().bodyJson()
                .extractingPath("$.accounts['suspense:nao_identificado']").asString().startsWith("-");
    }

    @Test
    void mesmoWebhookEmSequenciaCreditaUmaVez() {
        var charge = novaCobranca("80.00");
        var e2eId = e2eId();

        assertThat(webhook(e2eId, charge.getTxid(), "80.00")).hasStatusOk();
        assertThat(webhook(e2eId, charge.getTxid(), "80.00")).hasStatusOk();

        assertThat(payments.findByChargeIdOrderByPaidAt(charge.getId())).hasSize(1);
    }

    @Test
    void pixParaTxidDesconhecidoNaoCredita() {
        var e2eId = e2eId();

        assertThat(webhook(e2eId, "desconhecido000000000000000001", "10.00")).hasStatusOk();

        assertThat(payments.existsByE2eId(e2eId)).isFalse();
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

    private static String e2eId() {
        return "E12345678202610061530" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }
}
