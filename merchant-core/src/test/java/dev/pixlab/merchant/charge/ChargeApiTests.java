package dev.pixlab.merchant.charge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import dev.pixlab.contracts.pix.CobRequest;
import dev.pixlab.merchant.TestcontainersConfiguration;
import dev.pixlab.merchant.psp.PspClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.client.RestClientException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChargeApiTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ChargeRepository charges;

    @MockitoBean
    PspClient psp;

    @Test
    void criaCobrancaAtivaERegistraNoPsp() {
        var result = assertThat(create("{\"valor\":\"150.00\",\"descricao\":\"Pedido #4821\"}"))
                .hasStatus(HttpStatus.CREATED).bodyJson();
        result.extractingPath("$.status").isEqualTo("ATIVA");
        result.extractingPath("$.valor").isEqualTo("150.00");

        var request = ArgumentCaptor.forClass(CobRequest.class);
        verify(psp).criarCob(any(), request.capture());
        assertThat(request.getValue().valor().original()).isEqualTo("150.00");
        assertThat(request.getValue().chave()).isEqualTo("recebedor@pixlab.dev");
        assertThat(request.getValue().calendario().expiracao()).isEqualTo(3600);
    }

    @Test
    void naoGravaCobrancaSePspRecusar() {
        var antes = charges.count();
        given(psp.criarCob(any(), any())).willThrow(new RestClientException("PSP fora"));

        assertThat(create("{\"valor\":\"10.00\"}")).hasStatus(HttpStatus.BAD_GATEWAY);

        assertThat(charges.count()).isEqualTo(antes);
    }

    @Test
    void rejeitaValorInvalido() {
        assertThat(create("{\"valor\":\"10\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(create("{\"valor\":\"0.00\"}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult create(String body) {
        return mvc.post().uri("/charges").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }
}
