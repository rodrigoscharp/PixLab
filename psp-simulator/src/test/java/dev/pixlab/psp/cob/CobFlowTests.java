package dev.pixlab.psp.cob;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pixlab.psp.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CobFlowTests {

    private static final String COB = """
            {"calendario":{"expiracao":3600},"valor":{"original":"150.00"},"chave":"recebedor@pixlab.dev",
             "solicitacaoPagador":"Pedido #4821"}""";

    @Autowired
    MockMvcTester mvc;

    @Test
    void criaConsultaEPagaCobranca() {
        var txid = "pixlab7f3c9a1b2d4e5f60718293a4";

        assertThat(put(txid, COB)).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.status").isEqualTo("ATIVA");

        var pagamento = mvc.post().uri("/sim/cob/{txid}/pagamento", txid)
                .contentType(MediaType.APPLICATION_JSON).content("{\"infoPagador\":\"Pedido #4821\"}");
        assertThat(pagamento).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.endToEndId").asString().matches("E12345678\\d{12}[a-z0-9]{11}");

        var cob = assertThat(mvc.get().uri("/cob/{txid}", txid)).hasStatusOk().bodyJson();
        cob.extractingPath("$.status").isEqualTo("CONCLUIDA");
        cob.extractingPath("$.pix[0].valor").isEqualTo("150.00");
        cob.extractingPath("$.pix[0].txid").isEqualTo(txid);
    }

    @Test
    void naoPagaDuasVezes() {
        var txid = "duplicado00000000000000000001";
        assertThat(put(txid, COB)).hasStatus(HttpStatus.CREATED);
        assertThat(mvc.post().uri("/sim/cob/{txid}/pagamento", txid)).hasStatus(HttpStatus.CREATED);

        assertThat(mvc.post().uri("/sim/cob/{txid}/pagamento", txid)).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void rejeitaTxidForaDoFormato() {
        assertThat(put("curto", COB)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejeitaTxidRepetido() {
        var txid = "repetido000000000000000000001";
        assertThat(put(txid, COB)).hasStatus(HttpStatus.CREATED);

        assertThat(put(txid, COB)).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void rejeitaValorForaDoFormato() {
        assertThat(put("valorinvalido0000000000000001", COB.replace("150.00", "150.5")))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void cobrancaInexistenteDa404() {
        assertThat(mvc.get().uri("/cob/{txid}", "naoexiste00000000000000000001")).hasStatus(HttpStatus.NOT_FOUND);
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult put(String txid, String body) {
        return mvc.put().uri("/cob/{txid}", txid).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }
}
