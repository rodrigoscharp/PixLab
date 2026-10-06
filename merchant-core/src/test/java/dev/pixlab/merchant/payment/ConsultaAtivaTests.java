package dev.pixlab.merchant.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.merchant.TestData;
import dev.pixlab.merchant.TestcontainersConfiguration;
import dev.pixlab.merchant.charge.Charge;
import dev.pixlab.merchant.charge.ChargeRepository;
import dev.pixlab.merchant.charge.ChargeStatus;
import dev.pixlab.merchant.psp.PspClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Cenário PIX-LOST: o webhook nunca chega, e a consulta ativa encontra o Pix no extrato. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConsultaAtivaTests {

    @Autowired
    ConsultaAtiva consulta;

    @Autowired
    ChargeRepository charges;

    @MockitoBean
    PspClient psp;

    @Test
    void pixPerdidoEhRecuperadoUmaVezSo() {
        var now = Instant.now();
        var txid = UUID.randomUUID().toString().replace("-", "");
        charges.save(new Charge(txid, new BigDecimal("33.00"), "perdido", now, now.plus(1, ChronoUnit.HOURS)));
        var pix = new Pix(TestData.e2eId(), txid, "33.00", now.toString(), null, List.of());
        given(psp.listarPix(any(), any())).willReturn(List.of(pix));

        var primeira = consulta.run(now.minusSeconds(60), now.plusSeconds(60));
        var segunda = consulta.run(now.minusSeconds(60), now.plusSeconds(60));

        assertThat(primeira.injected()).isEqualTo(1);
        assertThat(segunda.injected()).isZero();
        await().untilAsserted(() ->
                assertThat(charges.findByTxid(txid).orElseThrow().getStatus()).isEqualTo(ChargeStatus.CONCLUIDA));
    }
}
