package dev.pixlab.contracts.boleto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Arquivo de retorno de cobrança CNAB 240 (layout Febraban genérico), com segmentos T e U. Posições 1-based,
 * campos numéricos alinhados à direita com zeros, alfanuméricos à esquerda com espaços.
 *
 * <pre>
 * Comum      1-3 banco | 4-7 lote | 8 tipo (0 header arq., 1 header lote, 3 detalhe, 5 trailer lote, 9 trailer arq.)
 * Detalhe    9-13 nº sequencial | 14 segmento (T/U) | 16-17 código de ocorrência
 * Segmento T 38-57 nosso número | 74-81 vencimento (DDMMAAAA) | 82-96 valor do título | 106-130 uso da empresa
 * Segmento U 18-32 juros/multa | 78-92 valor pago | 93-107 valor líquido | 138-145 data da ocorrência
 *            146-153 data do crédito
 * Header arq 143 código (2 = retorno) | 144-151 data de geração | 152-157 hora | 158-163 NSA
 * Trailers   18-23 quantidade (lotes no de arquivo, registros no de lote) | 24-29 registros (arquivo)
 * </pre>
 */
public final class Cnab240 {

    public static final String ENTRADA_CONFIRMADA = "02";
    public static final String LIQUIDACAO = "06";
    public static final String BAIXA = "09";
    public static final int TAMANHO = 240;

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("ddMMuuuu");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HHmmss");

    /**
     * Uma ocorrência de título, vinda de um par T+U.
     *
     * @param idOcorrencia identificador da ocorrência no banco, levado em "uso da empresa"; chave de idempotência
     */
    public record Ocorrencia(String nossoNumero, String codigo, String idOcorrencia, LocalDate vencimento,
            BigDecimal valorTitulo, BigDecimal jurosMulta, BigDecimal valorPago, LocalDate dataOcorrencia,
            LocalDate dataCredito) {}

    public record LineError(int linha, String motivo) {}

    public record Parsed(List<Ocorrencia> ocorrencias, List<LineError> erros, int registros) {}

    private Cnab240() {}

    public static String write(String banco, long nsa, LocalDate geracao, LocalTime hora, List<Ocorrencia> ocorrencias) {
        var lines = new ArrayList<String>();
        lines.add(new Line(banco, "0000", '0').put(143, "2").put(144, DATA.format(geracao))
                .put(152, HORA.format(hora)).num(158, 6, nsa).toString());
        lines.add(new Line(banco, "0001", '1').put(9, "T").toString());
        int seq = 0;
        for (var o : ocorrencias) {
            lines.add(new Line(banco, "0001", '3').num(9, 5, ++seq).put(14, "T").put(16, o.codigo())
                    .num(38, 20, Long.parseLong(o.nossoNumero())).put(74, DATA.format(o.vencimento()))
                    .money(82, 15, o.valorTitulo()).text(106, 25, o.idOcorrencia()).toString());
            lines.add(new Line(banco, "0001", '3').num(9, 5, ++seq).put(14, "U").put(16, o.codigo())
                    .money(18, 15, o.jurosMulta()).money(33, 15, BigDecimal.ZERO).money(48, 15, BigDecimal.ZERO)
                    .money(63, 15, BigDecimal.ZERO).money(78, 15, o.valorPago()).money(93, 15, o.valorPago())
                    .put(138, DATA.format(o.dataOcorrencia()))
                    .put(146, o.dataCredito() == null ? "00000000" : DATA.format(o.dataCredito())).toString());
        }
        lines.add(new Line(banco, "0001", '5').num(18, 6, seq + 2).toString());
        lines.add(new Line(banco, "9999", '9').num(18, 6, 1).num(24, 6, lines.size() + 1).toString());
        return String.join("\r\n", lines) + "\r\n";
    }

    /**
     * Lê um arquivo de retorno. Linhas inválidas não derrubam o arquivo: viram erros, e só o par T+U afetado é
     * descartado (cenário BOL-FILE-CORRUPT).
     */
    public static Parsed parse(String content) {
        var lines = content.split("\r?\n");
        var ocorrencias = new ArrayList<Ocorrencia>();
        var erros = new ArrayList<LineError>();
        int registros = 0;
        String[] pendingT = null;
        int pendingLine = 0;
        for (int i = 0; i < lines.length; i++) {
            var line = lines[i];
            int n = i + 1;
            if (line.isEmpty()) {
                continue;
            }
            registros++;
            if (line.length() != TAMANHO) {
                erros.add(new LineError(n, "tamanho " + line.length() + " ≠ 240"));
                pendingT = null;
                continue;
            }
            if (line.charAt(7) != '3') {
                continue;
            }
            char segmento = line.charAt(13);
            try {
                if (segmento == 'T') {
                    pendingT = new String[] {line};
                    pendingLine = n;
                    // valida já, para reportar a linha certa
                    parseT(line);
                } else if (segmento == 'U') {
                    if (pendingT == null) {
                        erros.add(new LineError(n, "segmento U sem T correspondente"));
                        continue;
                    }
                    ocorrencias.add(combine(parseT(pendingT[0]), line));
                    pendingT = null;
                } else {
                    erros.add(new LineError(n, "segmento desconhecido: " + segmento));
                    pendingT = null;
                }
            } catch (RuntimeException e) {
                erros.add(new LineError(segmento == 'T' ? pendingLine : n, e.getMessage()));
                pendingT = null;
            }
        }
        if (pendingT != null) {
            erros.add(new LineError(pendingLine, "segmento T sem U correspondente"));
        }
        return new Parsed(ocorrencias, erros, registros);
    }

    private record T(String codigo, String nossoNumero, LocalDate vencimento, BigDecimal valor, String id) {}

    private static T parseT(String line) {
        return new T(digits(line, 16, 2, "código de ocorrência"),
                Long.toString(Long.parseLong(digits(line, 38, 20, "nosso número"))),
                date(line, 74, "vencimento"),
                money(line, 82, 15, "valor do título"),
                field(line, 106, 25).strip());
    }

    private static Ocorrencia combine(T t, String u) {
        var codigoU = digits(u, 16, 2, "código de ocorrência");
        if (!codigoU.equals(t.codigo())) {
            throw new IllegalArgumentException("ocorrência do U (" + codigoU + ") ≠ do T (" + t.codigo() + ")");
        }
        var credito = field(u, 146, 8);
        return new Ocorrencia(t.nossoNumero(), t.codigo(), t.id(), t.vencimento(), t.valor(),
                money(u, 18, 15, "juros/multa"), money(u, 78, 15, "valor pago"), date(u, 138, "data da ocorrência"),
                credito.isBlank() || credito.equals("00000000") ? null : date(u, 146, "data do crédito"));
    }

    private static String field(String line, int start, int length) {
        return line.substring(start - 1, start - 1 + length);
    }

    private static String digits(String line, int start, int length, String name) {
        var value = field(line, start, length);
        if (!value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(name + " não numérico: '" + value + "'");
        }
        return value;
    }

    private static BigDecimal money(String line, int start, int length, String name) {
        return new BigDecimal(digits(line, start, length, name)).movePointLeft(2);
    }

    private static LocalDate date(String line, int start, String name) {
        try {
            return LocalDate.parse(digits(line, start, 8, name), DATA);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " inválida: " + field(line, start, 8));
        }
    }

    /** Linha de 240 posições preenchida com espaços. */
    private static final class Line {

        private final char[] chars = " ".repeat(TAMANHO).toCharArray();

        Line(String banco, String lote, char tipo) {
            put(1, banco).put(4, lote).put(8, String.valueOf(tipo));
        }

        Line put(int start, String value) {
            value.getChars(0, value.length(), chars, start - 1);
            return this;
        }

        Line num(int start, int length, long value) {
            var s = String.format("%0" + length + "d", value);
            if (s.length() > length) {
                throw new IllegalArgumentException("valor não cabe em " + length + " posições: " + value);
            }
            return put(start, s);
        }

        Line money(int start, int length, BigDecimal value) {
            return num(start, length, value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact());
        }

        Line text(int start, int length, String value) {
            var s = value == null ? "" : value;
            if (s.length() > length) {
                throw new IllegalArgumentException("texto não cabe em " + length + " posições: " + s);
            }
            return put(start, s + " ".repeat(length - s.length()));
        }

        @Override
        public String toString() {
            return new String(chars);
        }
    }
}
