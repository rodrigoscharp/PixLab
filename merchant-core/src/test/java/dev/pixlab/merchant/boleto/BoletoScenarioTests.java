package dev.pixlab.merchant.boleto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import dev.pixlab.contracts.boleto.Boleto;
import dev.pixlab.contracts.boleto.BoletoCodigo;
import dev.pixlab.contracts.boleto.BoletoRequest;
import dev.pixlab.contracts.boleto.Cnab240;
import dev.pixlab.contracts.boleto.Cnab240.Ocorrencia;
import dev.pixlab.merchant.TestcontainersConfiguration;
import dev.pixlab.merchant.charge.ChargeRepository;
import dev.pixlab.merchant.charge.ChargeStatus;
import dev.pixlab.merchant.inbox.InboxProcessor;
import dev.pixlab.merchant.psp.PspClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** F6: cenários BOL-* com arquivos de retorno CNAB 240 de verdade passando pela API. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "pixlab.inbox.workers=0")
@AutoConfigureMockMvc
class BoletoScenarioTests {

    private static final AtomicLong NOSSO_NUMERO = new AtomicLong(System.nanoTime() % 1_000_000_000L * 10);
    private static final LocalDate VENCIMENTO = LocalDate.of(2027, 3, 10);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ReturnFiles returnFiles;

    @Autowired
    InboxProcessor processor;

    @Autowired
    ChargeRepository charges;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    PspClient psp;

    @BeforeEach
    void bank() {
        given(psp.emitirBoleto(any())).willAnswer(inv -> {
            BoletoRequest r = inv.getArgument(0);
            var nn = Long.toString(NOSSO_NUMERO.incrementAndGet());
            var codigo = BoletoCodigo.codigoBarras("999", LocalDate.parse(r.vencimento()), new BigDecimal(r.valor()),
                    "%011d".formatted(Long.parseLong(nn)) + "00010000012345");
            return new Boleto(nn, r.valor(), r.vencimento(), r.multaPercentual(), r.jurosMensalPercentual(), codigo,
                    BoletoCodigo.linhaDigitavel(codigo), "REGISTRADO");
        });
    }

    @Test
    void emiteComCodigoDeBarrasValido() {
        var boleto = emitir("1000.00");

        assertThat(BoletoCodigo.codigoBarrasValido((String) boleto.get("codigoBarras"))).isTrue();
        assertThat((String) boleto.get("linhaDigitavel")).hasSize(47);
    }

    @Test
    void pagoNoValorConclui() {
        var nn = nossoNumero(emitir("1000.00"));

        ingest(liquidacao(nn, "1000.00", VENCIMENTO));

        assertThat(status(nn)).isEqualTo(ChargeStatus.CONCLUIDA);
        assertThat(net(nn, "merchant:receita")).isEqualByComparingTo("-1000.00");
    }

    @Test
    void bolOverConcluiEExcedenteViraCreditoADevolver() {
        var nn = nossoNumero(emitir("1000.00"));

        ingest(liquidacao(nn, "1100.00", VENCIMENTO));

        assertThat(status(nn)).isEqualTo(ChargeStatus.CONCLUIDA);
        assertThat(net(nn, "merchant:receita")).isEqualByComparingTo("-1000.00");
        assertThat(net(nn, "merchant:credito_a_devolver")).isEqualByComparingTo("-100.00");
    }

    @Test
    void bolUnderFicaDivergenteEmSuspense() {
        var nn = nossoNumero(emitir("1000.00"));

        ingest(liquidacao(nn, "900.00", VENCIMENTO));

        assertThat(status(nn)).isEqualTo(ChargeStatus.DIVERGENTE);
        assertThat(net(nn, "suspense:nao_identificado")).isEqualByComparingTo("-900.00");
        assertThat(net(nn, "merchant:receita")).isEqualByComparingTo("0");
    }

    @Test
    void bolDoubleSegundoPagamentoViraCreditoADevolver() {
        var nn = nossoNumero(emitir("1000.00"));

        ingest(liquidacao(nn, "1000.00", VENCIMENTO), liquidacao(nn, "1000.00", VENCIMENTO));

        assertThat(status(nn)).isEqualTo(ChargeStatus.CONCLUIDA);
        assertThat(net(nn, "merchant:receita")).isEqualByComparingTo("-1000.00");
        assertThat(net(nn, "merchant:credito_a_devolver")).isEqualByComparingTo("-1000.00");
    }

    @Test
    void bolLateRecalculaOValorEsperadoComMultaEJuros() {
        var nn = nossoNumero(emitir("1000.00"));
        // 15 dias de atraso: 2% de multa (20,00) + 1% a.m. pro rata (5,00).
        var pagamento = VENCIMENTO.plusDays(15);

        ingest(liquidacao(nn, "1025.00", pagamento));

        assertThat(status(nn)).isEqualTo(ChargeStatus.CONCLUIDA);
        assertThat(net(nn, "merchant:receita")).isEqualByComparingTo("-1025.00");
    }

    @Test
    void bolLatePagandoSoOOriginalFicaDivergente() {
        var nn = nossoNumero(emitir("1000.00"));

        ingest(liquidacao(nn, "1000.00", VENCIMENTO.plusDays(15)));

        assertThat(status(nn)).isEqualTo(ChargeStatus.DIVERGENTE);
    }

    @Test
    void bolFileDupMesmoArquivoDezVezesNaoMudaOLedger() {
        var nn1 = nossoNumero(emitir("100.00"));
        var nn2 = nossoNumero(emitir("200.00"));
        var file = file(liquidacao(nn1, "100.00", VENCIMENTO), liquidacao(nn2, "250.00", VENCIMENTO));

        var first = returnFiles.ingest("RET.ret", file);
        processor.drain();
        var before = ledgerSnapshot();
        for (int i = 0; i < 10; i++) {
            assertThat(returnFiles.ingest("RET-copia-" + i + ".ret", file).duplicate()).isTrue();
            processor.drain();
        }

        assertThat(first.newEvents()).isEqualTo(2);
        assertThat(ledgerSnapshot()).isEqualTo(before);
        assertThat(returnFiles.reconcile(first.id())).containsEntry("fechado", true)
                .containsEntry("totalLiquidadoArquivo", "350.00").containsEntry("totalLancadoLedger", "350.00");
    }

    @Test
    void mesmoRegistroEmArquivosDiferentesCreditaUmaVez() {
        var nn = nossoNumero(emitir("100.00"));
        var liquidacao = liquidacao(nn, "100.00", VENCIMENTO);

        returnFiles.ingest("A.ret", file(liquidacao));
        var b = returnFiles.ingest("B.ret", file(liquidacao, entrada(nossoNumero(emitir("50.00")))));
        processor.drain();

        assertThat(b.duplicate()).isFalse();
        assertThat(b.newEvents()).isEqualTo(1);
        assertThat(net(nn, "banco:boleto:liquidar")).isEqualByComparingTo("100.00");
    }

    @Test
    void bolFileCorruptProcessaAsLinhasValidasEReportaAInvalida() {
        var nn1 = nossoNumero(emitir("100.00"));
        var nn2 = nossoNumero(emitir("200.00"));
        var l1 = liquidacao(nn1, "100.00", VENCIMENTO);
        var l2 = liquidacao(nn2, "200.00", VENCIMENTO);
        var lines = file(l1, l2).split("\r\n");
        lines[3] = lines[3].substring(0, 80) + "XX" + lines[3].substring(82);

        var result = assertThat(mvc.post().uri("/cnab/retornos?nome=corrompido.ret").contentType(MediaType.TEXT_PLAIN)
                .content(String.join("\r\n", lines))).hasStatusOk().bodyJson();
        processor.drain();

        result.extractingPath("$.occurrences").isEqualTo(1);
        result.extractingPath("$.errors[0].linha").isEqualTo(4);
        assertThat(status(nn1)).isEqualTo(ChargeStatus.ATIVA);
        assertThat(status(nn2)).isEqualTo(ChargeStatus.CONCLUIDA);

        // Arquivo corrigido (outro sha256, mesmas ocorrências) recupera o título perdido sem duplicar o outro.
        ingest(l1, l2);
        assertThat(status(nn1)).isEqualTo(ChargeStatus.CONCLUIDA);
        assertThat(net(nn2, "banco:boleto:liquidar")).isEqualByComparingTo("200.00");
    }

    @Test
    void baixaAntesDoPagamentoExpiraEPagamentoDepoisFicaDivergente() {
        var nn = nossoNumero(emitir("100.00"));

        ingest(new Ocorrencia(nn, Cnab240.BAIXA, "baixa-" + nn, VENCIMENTO, new BigDecimal("100.00"),
                new BigDecimal("0.00"), new BigDecimal("0.00"), VENCIMENTO, null));
        assertThat(status(nn)).isEqualTo(ChargeStatus.EXPIRADA);

        ingest(liquidacao(nn, "100.00", VENCIMENTO));
        assertThat(status(nn)).isEqualTo(ChargeStatus.DIVERGENTE);
    }

    private Map<String, Object> emitir(String valor) {
        var body = """
                {"valor":"%s","vencimento":"%s","multaPercentual":"2.00","jurosMensalPercentual":"1.00"}"""
                .formatted(valor, VENCIMENTO);
        var response = mvc.post().uri("/charges/boleto").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(response).hasStatus(201);
        try {
            return new tools.jackson.databind.json.JsonMapper().readValue(response.getResponse().getContentAsString(),
                    Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nossoNumero(Map<String, Object> boleto) {
        return (String) boleto.get("nossoNumero");
    }

    private static int seq;

    private static Ocorrencia liquidacao(String nn, String valor, LocalDate data) {
        var pago = new BigDecimal(valor);
        return new Ocorrencia(nn, Cnab240.LIQUIDACAO, "oc" + nn + "-" + (++seq), VENCIMENTO, pago.max(BigDecimal.ONE),
                new BigDecimal("0.00"), pago, data, data.plusDays(1));
    }

    private static Ocorrencia entrada(String nn) {
        return new Ocorrencia(nn, Cnab240.ENTRADA_CONFIRMADA, "oc" + nn + "-" + (++seq), VENCIMENTO,
                new BigDecimal("50.00"), new BigDecimal("0.00"), new BigDecimal("0.00"), VENCIMENTO, null);
    }

    private static String file(Ocorrencia... ocorrencias) {
        return Cnab240.write("999", 1, VENCIMENTO, LocalTime.NOON, List.of(ocorrencias));
    }

    private void ingest(Ocorrencia... ocorrencias) {
        returnFiles.ingest("RET.ret", file(ocorrencias));
        processor.drain();
    }

    private ChargeStatus status(String nn) {
        return charges.findByNossoNumero(nn).orElseThrow().getStatus();
    }

    private BigDecimal net(String nn, String account) {
        return jdbc.sql("select coalesce(sum(debit - credit), 0) from ledger_entry where ref like ? and account = ?")
                .param("BOL:" + nn + ":%").param(account).query(BigDecimal.class).single();
    }

    private List<Map<String, Object>> ledgerSnapshot() {
        return jdbc.sql("select id, account, debit, credit from ledger_entry order by id").query().listOfRows();
    }
}
