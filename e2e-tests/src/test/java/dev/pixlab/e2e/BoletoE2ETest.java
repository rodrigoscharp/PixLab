package dev.pixlab.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * F6 — boleto de ponta a ponta: o recebedor emite no banco simulado, pagadores pagam no valor, a maior, a menor,
 * em dobro e atrasado; o arquivo de retorno do dia é baixado do banco e enviado ao recebedor 10 vezes.
 */
class BoletoE2ETest {

    record BoletoCharge(String txid, String nossoNumero, String valor, String status, String codigoBarras) {}

    record Ingested(long id, boolean duplicate, int occurrences, int newEvents, List<Map<String, Object>> errors) {}

    static RestClient bank;
    static RestClient merchant;
    static final LocalDate VENCIMENTO = LocalDate.of(2028, 3, 10);
    static final LocalDate PAGAMENTO = LocalDate.of(2028, 3, 25);

    @BeforeAll
    static void start() throws Exception {
        bank = Lab.get().pspApi;
        merchant = Lab.get().merchantApi;
    }

    @Test
    void retornoDoDiaReprocessadoDezVezesNaoMudaOLedger() {
        var exato = emitir("100.00");
        var maior = emitir("100.00");
        var menor = emitir("100.00");
        var dobro = emitir("100.00");
        var atrasado = emitir("1000.00");

        pagar(exato, "100.00", VENCIMENTO);
        pagar(maior, "130.00", PAGAMENTO.minusDays(14));
        pagar(menor, "80.00", PAGAMENTO.minusDays(14));
        pagar(dobro, "100.00", VENCIMENTO);
        pagar(dobro, "100.00", VENCIMENTO);
        pagar(atrasado, null, PAGAMENTO); // banco cobra multa 2% + juros 1% a.m. por 15 dias

        var dia1 = retorno(VENCIMENTO, false);
        var dia2 = retorno(PAGAMENTO.minusDays(14), false);
        var dia3 = retorno(PAGAMENTO, false);

        var first = upload("RET_1.ret", dia1);
        upload("RET_2.ret", dia2);
        upload("RET_3.ret", dia3);
        await().atMost(Duration.ofSeconds(10)).until(() -> pending() == 0);
        assertThat(first.duplicate()).isFalse();

        assertThat(status(exato)).isEqualTo("CONCLUIDA");
        assertThat(status(maior)).isEqualTo("CONCLUIDA");
        assertThat(status(menor)).isEqualTo("DIVERGENTE");
        assertThat(status(dobro)).isEqualTo("CONCLUIDA");
        assertThat(status(atrasado)).isEqualTo("CONCLUIDA");

        var before = balances();
        for (int i = 0; i < 10; i++) {
            assertThat(upload("RET_1_copia_" + i + ".ret", dia1).duplicate()).isTrue();
            assertThat(upload("RET_3_copia_" + i + ".ret", dia3).duplicate()).isTrue();
        }
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(10)).until(() -> pending() == 0);
        assertThat(balances()).isEqualTo(before);
        assertThat(before.get("total")).isEqualTo("0.00");

        var recon = merchant.get().uri("/cnab/retornos/{id}", first.id()).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(recon).containsEntry("fechado", true).containsEntry("totalLiquidadoArquivo", "300.00");
    }

    @Test
    void arquivoCorrompidoProcessaOValidoEOCorrigidoRecuperaORestante() {
        var dia = LocalDate.of(2028, 3, 1);
        var a = emitir("10.00");
        var b = emitir("20.00");
        pagar(a, "10.00", dia);
        pagar(b, "20.00", dia);

        var corrompido = upload("RET_corrompido.ret", retorno(dia, true));
        await().atMost(Duration.ofSeconds(10)).until(() -> pending() == 0);
        assertThat(corrompido.errors()).hasSize(1);
        assertThat(corrompido.occurrences()).isEqualTo(1); // das 2 liquidações do dia, 1 sobreviveu

        upload("RET_corrigido.ret", retorno(dia, false));
        await().atMost(Duration.ofSeconds(10)).until(() -> status(a).equals("CONCLUIDA") && status(b).equals("CONCLUIDA"));
    }

    private BoletoCharge emitir(String valor) {
        return merchant.post().uri("/charges/boleto")
                .body(Map.of("valor", valor, "vencimento", VENCIMENTO.toString(), "multaPercentual", "2.00",
                        "jurosMensalPercentual", "1.00"))
                .retrieve().body(BoletoCharge.class);
    }

    private void pagar(BoletoCharge boleto, String valor, LocalDate data) {
        var body = valor == null ? Map.of("data", data.toString()) : Map.of("valor", valor, "data", data.toString());
        bank.post().uri("/sim/boletos/{nn}/pagamento", boleto.nossoNumero()).body(body).retrieve().toBodilessEntity();
    }

    private String retorno(LocalDate data, boolean corromper) {
        return bank.get().uri("/cnab/retorno?data={d}&corromper={c}", data, corromper).retrieve().body(String.class);
    }

    private Ingested upload(String nome, String content) {
        return merchant.post().uri("/cnab/retornos?nome={n}", nome).contentType(MediaType.TEXT_PLAIN).body(content)
                .retrieve().body(Ingested.class);
    }

    private String status(BoletoCharge boleto) {
        return merchant.get().uri("/charges/boleto/{nn}", boleto.nossoNumero()).retrieve().body(BoletoCharge.class).status();
    }

    private long pending() {
        return merchant.get().uri("/admin/inbox").retrieve()
                .body(new ParameterizedTypeReference<Map<String, Long>>() {}).get("PENDING");
    }

    private Map<String, Object> balances() {
        return merchant.get().uri("/ledger/balances").retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
