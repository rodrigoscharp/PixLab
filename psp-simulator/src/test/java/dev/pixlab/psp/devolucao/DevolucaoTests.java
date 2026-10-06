package dev.pixlab.psp.devolucao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.pixlab.contracts.pix.Calendario;
import dev.pixlab.contracts.pix.CobRequest;
import dev.pixlab.contracts.pix.Devolucao;
import dev.pixlab.contracts.pix.DevolucaoRequest;
import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.psp.TestcontainersConfiguration;
import dev.pixlab.psp.chaos.ChaosEngine;
import dev.pixlab.psp.chaos.ChaosProfile;
import dev.pixlab.psp.chaos.ChaosProfile.ScenarioConfig;
import dev.pixlab.psp.cob.CobService;
import dev.pixlab.psp.pix.PagadorSimulado;
import dev.pixlab.psp.support.VirtualClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DevolucaoTests {

    @Autowired
    DevolucaoService devolucoes;

    @Autowired
    CobService cobs;

    @Autowired
    PagadorSimulado pagador;

    @Autowired
    ChaosEngine chaos;

    @Autowired
    VirtualClock clock;

    @AfterEach
    void reset() {
        chaos.reset();
    }

    @Test
    void devolucaoProcessaDeFormaAssincrona() {
        var e2eId = pix("100.00");

        var d = devolucoes.solicitar(e2eId, "dev1", new DevolucaoRequest("40.00", null));
        assertThat(d.status()).isEqualTo(Devolucao.EM_PROCESSAMENTO);
        assertThat(d.rtrId()).hasSize(32).startsWith("D12345678");

        clock.advance(Duration.ofSeconds(2));
        await().untilAsserted(() ->
                assertThat(devolucoes.consultar(e2eId, "dev1").status()).isEqualTo(Devolucao.DEVOLVIDO));
    }

    @Test
    void putComMesmoIdEhIdempotente() {
        var e2eId = pix("100.00");

        var a = devolucoes.solicitar(e2eId, "dev1", new DevolucaoRequest("40.00", null));
        var b = devolucoes.solicitar(e2eId, "dev1", new DevolucaoRequest("40.00", null));

        assertThat(b.rtrId()).isEqualTo(a.rtrId());
    }

    @Test
    void somaDasDevolucoesNaoPassaDoValor() {
        var e2eId = pix("100.00");
        devolucoes.solicitar(e2eId, "dev1", new DevolucaoRequest("60.00", null));
        devolucoes.med(e2eId, "30.00");

        assertThatThrownBy(() -> devolucoes.solicitar(e2eId, "dev2", new DevolucaoRequest("10.01", null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("excede");
    }

    @Test
    void refundFailTerminaNaoRealizadoELiberaOSaldo() {
        chaos.apply(new ChaosProfile("fail", 1, Map.of("PIX-REFUND-FAIL", new ScenarioConfig(1, null, null, null, null))));
        var e2eId = pix("100.00");
        devolucoes.solicitar(e2eId, "dev1", new DevolucaoRequest("100.00", null));

        clock.advance(Duration.ofSeconds(2));
        await().untilAsserted(() ->
                assertThat(devolucoes.consultar(e2eId, "dev1").status()).isEqualTo(Devolucao.NAO_REALIZADO));

        assertThat(devolucoes.solicitar(e2eId, "dev2", new DevolucaoRequest("100.00", null)).status())
                .isEqualTo(Devolucao.EM_PROCESSAMENTO);
    }

    @Test
    void medSemValorDevolveOSaldoInteiro() {
        var e2eId = pix("100.00");
        devolucoes.solicitar(e2eId, "dev1", new DevolucaoRequest("25.00", null));

        var med = devolucoes.med(e2eId, null);

        assertThat(med.valor()).isEqualTo("75.00");
        assertThat(med.natureza()).isEqualTo(Devolucao.MED);
        assertThat(med.status()).isEqualTo(Devolucao.DEVOLVIDO);
    }

    private String pix(String valor) {
        var txid = UUID.randomUUID().toString().replace("-", "");
        cobs.criar(txid, new CobRequest(Calendario.expiraEm(3600), Valor.of(new BigDecimal(valor)), "chave", null));
        return pagador.pagar(txid, null).endToEndId();
    }
}
