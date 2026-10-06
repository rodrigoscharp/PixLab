package dev.pixlab.psp.boleto;

import dev.pixlab.contracts.boleto.Boleto;
import dev.pixlab.contracts.boleto.BoletoCodigo;
import dev.pixlab.contracts.boleto.BoletoRequest;
import dev.pixlab.contracts.boleto.Cnab240;
import dev.pixlab.contracts.boleto.Encargos;
import dev.pixlab.contracts.pix.Valor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** O simulador como banco emissor de boletos: registro, liquidação, baixa e arquivo de retorno CNAB 240. */
@Service
public class BoletoService {

    private static final Logger log = LoggerFactory.getLogger(BoletoService.class);
    static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final String AGENCIA = "0001";
    private static final String CONTA = "0000012345";

    record BoletoRow(long nossoNumero, String beneficiario, BigDecimal valor, LocalDate vencimento, BigDecimal multa,
            BigDecimal juros, String status, String codigoBarras, String linhaDigitavel) {

        Boleto toContract() {
            return new Boleto(Long.toString(nossoNumero), Valor.format(valor), vencimento.toString(),
                    multa.toPlainString(), juros.toPlainString(), codigoBarras, linhaDigitavel, status);
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final String banco;

    BoletoService(JdbcClient jdbc, Clock clock, @Value("${pixlab.psp.banco}") String banco) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.banco = banco;
    }

    @Transactional
    public Boleto emitir(BoletoRequest request) {
        var valor = parseValor(request.valor());
        var vencimento = parseData(request.vencimento(), "vencimento");
        var multa = percentual(request.multaPercentual());
        var juros = percentual(request.jurosMensalPercentual());
        long nossoNumero = jdbc.sql("select nextval('nosso_numero_seq')").query(Long.class).single();
        var campoLivre = String.format("%011d", nossoNumero) + AGENCIA + CONTA;
        String codigoBarras;
        try {
            codigoBarras = BoletoCodigo.codigoBarras(banco, vencimento, valor, campoLivre);
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
        var linha = BoletoCodigo.linhaDigitavel(codigoBarras);
        jdbc.sql("""
                        insert into boleto (nosso_numero, beneficiario, valor, vencimento, multa_pct, juros_mes_pct, status,
                                            codigo_barras, linha_digitavel)
                        values (:nn, :benef, :valor, :venc, :multa, :juros, 'REGISTRADO', :cb, :ld)""")
                .param("nn", nossoNumero).param("benef", request.beneficiario()).param("valor", valor)
                .param("venc", vencimento).param("multa", multa).param("juros", juros)
                .param("cb", codigoBarras).param("ld", linha)
                .update();
        ocorrencia(nossoNumero, Cnab240.ENTRADA_CONFIRMADA, hoje(), null, BigDecimal.ZERO, BigDecimal.ZERO);
        return buscar(nossoNumero).toContract();
    }

    @Transactional(readOnly = true)
    public Boleto consultar(long nossoNumero) {
        return buscar(nossoNumero).toContract();
    }

    /**
     * Pagador simulado. Sem {@code valor}, paga o devido na data (com multa e juros se atrasado). Com valor, pode
     * pagar a maior (BOL-OVER) ou a menor (BOL-UNDER); pagar de novo um título liquidado é BOL-DOUBLE.
     */
    @Transactional
    public Cnab240.Ocorrencia pagar(long nossoNumero, String valorTexto, String dataTexto) {
        var boleto = travar(nossoNumero);
        if (boleto.status().equals("BAIXADO")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "boleto baixado");
        }
        var data = dataTexto == null ? hoje() : parseData(dataTexto, "data");
        var encargos = Encargos.encargos(boleto.valor(), boleto.vencimento(), boleto.multa(), boleto.juros(), data);
        var pago = valorTexto == null ? boleto.valor().add(encargos) : parseValor(valorTexto);
        jdbc.sql("update boleto set status = 'LIQUIDADO' where nosso_numero = ?").param(nossoNumero).update();
        var id = ocorrencia(nossoNumero, Cnab240.LIQUIDACAO, data, data.plusDays(1), pago, encargos);
        log.info("Boleto {} pago: {} em {} (devido {})", nossoNumero, pago, data, boleto.valor().add(encargos));
        return new Cnab240.Ocorrencia(Long.toString(nossoNumero), Cnab240.LIQUIDACAO, id, boleto.vencimento(),
                boleto.valor(), encargos, pago, data, data.plusDays(1));
    }

    @Transactional
    public void baixar(long nossoNumero) {
        var boleto = travar(nossoNumero);
        if (!boleto.status().equals("REGISTRADO")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "só boleto em aberto pode ser baixado");
        }
        jdbc.sql("update boleto set status = 'BAIXADO' where nosso_numero = ?").param(nossoNumero).update();
        ocorrencia(nossoNumero, Cnab240.BAIXA, hoje(), null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Arquivo de retorno com as ocorrências de um dia. {@code corromper} estraga uma linha (BOL-FILE-CORRUPT). */
    @Transactional(readOnly = true)
    public String retorno(LocalDate data, boolean corromper) {
        var ocorrencias = jdbc.sql("""
                        select o.id, o.nosso_numero, o.codigo, o.data_ocorrencia, o.data_credito, o.valor_pago,
                               o.juros_multa, b.vencimento, b.valor
                        from boleto_ocorrencia o join boleto b on b.nosso_numero = o.nosso_numero
                        where o.data_ocorrencia = ? order by o.id""")
                .param(data)
                .query((rs, n) -> new Cnab240.Ocorrencia(Long.toString(rs.getLong("nosso_numero")), rs.getString("codigo"),
                        "oc" + rs.getLong("id"), rs.getObject("vencimento", LocalDate.class), rs.getBigDecimal("valor"),
                        rs.getBigDecimal("juros_multa"), rs.getBigDecimal("valor_pago"),
                        rs.getObject("data_ocorrencia", LocalDate.class), rs.getObject("data_credito", LocalDate.class)))
                .list();
        var file = Cnab240.write(banco, data.toEpochDay(), data, LocalTime.of(23, 59), ocorrencias);
        return corromper ? corromper(file) : file;
    }

    private static String corromper(String file) {
        var lines = file.split("\r\n");
        // O segmento U do primeiro título: o valor pago ganha letras no meio.
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].length() == Cnab240.TAMANHO && lines[i].charAt(13) == 'U') {
                lines[i] = lines[i].substring(0, 80) + "XX" + lines[i].substring(82);
                log.info("[BOL-FILE-CORRUPT] linha {} corrompida", i + 1);
                break;
            }
        }
        return String.join("\r\n", lines) + "\r\n";
    }

    private String ocorrencia(long nossoNumero, String codigo, LocalDate data, LocalDate credito, BigDecimal pago,
            BigDecimal encargos) {
        long id = jdbc.sql("""
                        insert into boleto_ocorrencia (nosso_numero, codigo, data_ocorrencia, data_credito, valor_pago, juros_multa)
                        values (:nn, :codigo, :data, :credito, :pago, :encargos) returning id""")
                .param("nn", nossoNumero).param("codigo", codigo).param("data", data).param("credito", credito)
                .param("pago", pago).param("encargos", encargos)
                .query(Long.class).single();
        return "oc" + id;
    }

    private BoletoRow buscar(long nossoNumero) {
        return jdbc.sql("select * from boleto where nosso_numero = ?").param(nossoNumero)
                .query(BoletoService::map).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "boleto não encontrado: " + nossoNumero));
    }

    private BoletoRow travar(long nossoNumero) {
        return jdbc.sql("select * from boleto where nosso_numero = ? for update").param(nossoNumero)
                .query(BoletoService::map).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "boleto não encontrado: " + nossoNumero));
    }

    private static BoletoRow map(ResultSet rs, int n) throws SQLException {
        return new BoletoRow(rs.getLong("nosso_numero"), rs.getString("beneficiario"), rs.getBigDecimal("valor"),
                rs.getObject("vencimento", LocalDate.class), rs.getBigDecimal("multa_pct"),
                rs.getBigDecimal("juros_mes_pct"), rs.getString("status"), rs.getString("codigo_barras"),
                rs.getString("linha_digitavel"));
    }

    private LocalDate hoje() {
        return LocalDate.now(clock.withZone(SAO_PAULO));
    }

    private static BigDecimal parseValor(String valor) {
        try {
            var v = Valor.parse(valor);
            if (v.signum() <= 0) {
                throw badRequest("valor deve ser positivo");
            }
            return v;
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
    }

    private static LocalDate parseData(String data, String campo) {
        try {
            return LocalDate.parse(data);
        } catch (DateTimeParseException | NullPointerException e) {
            throw badRequest(campo + " inválido: " + data);
        }
    }

    private static BigDecimal percentual(String valor) {
        if (valor == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        try {
            var p = new BigDecimal(valor);
            if (p.signum() < 0 || p.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw badRequest("percentual fora de 0..100: " + valor);
            }
            return p;
        } catch (NumberFormatException e) {
            throw badRequest("percentual inválido: " + valor);
        }
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
