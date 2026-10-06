package dev.pixlab.merchant.boleto;

import dev.pixlab.contracts.boleto.Cnab240;
import dev.pixlab.merchant.inbox.Inbox;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ingestão de arquivos de retorno CNAB 240. Idempotência em duas camadas: por arquivo (sha256 do conteúdo) e por
 * registro (chave nossoNumero:idOcorrencia na inbox). Linhas inválidas viram erros sem perder as válidas.
 */
@Service
public class ReturnFiles {

    public record Ingested(long id, String name, String sha256, boolean duplicate, int records, int occurrences,
            int newEvents, List<Cnab240.LineError> errors) {}

    private static final Logger log = LoggerFactory.getLogger(ReturnFiles.class);

    private final JdbcClient jdbc;
    private final Inbox inbox;
    private final JsonMapper json;

    ReturnFiles(JdbcClient jdbc, Inbox inbox, JsonMapper json) {
        this.jdbc = jdbc;
        this.inbox = inbox;
        this.json = json;
    }

    @Transactional
    public Ingested ingest(String name, String content) {
        var sha = sha256(content);
        var inserted = jdbc.sql("""
                        insert into return_file (sha256, name) values (:sha, :name)
                        on conflict (sha256) do nothing returning id""")
                .param("sha", sha).param("name", name).query(Long.class).optional();
        if (inserted.isEmpty()) {
            var existing = find(jdbc.sql("select id from return_file where sha256 = ?").param(sha).query(Long.class).single());
            log.info("Arquivo {} já processado (sha256 {}); ignorado", name, sha);
            return new Ingested(existing.id(), name, sha, true, existing.records(), existing.occurrences(), 0,
                    existing.errors());
        }
        long id = inserted.get();
        var parsed = Cnab240.parse(content);
        int novas = 0;
        for (var o : parsed.ocorrencias()) {
            var key = BoletoEventHandler.key(o);
            if (inbox.offer(BoletoEventHandler.SOURCE, key, json.writeValueAsString(o))) {
                novas++;
            }
            jdbc.sql("""
                            insert into return_file_record (file_id, event_key, code, amount) values (:file, :key, :code, :amount)
                            on conflict do nothing""")
                    .param("file", id).param("key", key).param("code", o.codigo()).param("amount", o.valorPago())
                    .update();
        }
        jdbc.sql("""
                        update return_file set records = :records, occurrences = :occ, new_events = :novas,
                            errors = cast(:errors as jsonb)
                        where id = :id""")
                .param("records", parsed.registros()).param("occ", parsed.ocorrencias().size()).param("novas", novas)
                .param("errors", json.writeValueAsString(parsed.erros())).param("id", id)
                .update();
        if (!parsed.erros().isEmpty()) {
            log.warn("Arquivo {}: {} linha(s) inválida(s): {}", name, parsed.erros().size(), parsed.erros());
        }
        return new Ingested(id, name, sha, false, parsed.registros(), parsed.ocorrencias().size(), novas, parsed.erros());
    }

    public Ingested find(long id) {
        return jdbc.sql("select * from return_file where id = ?").param(id)
                .query((rs, n) -> new Ingested(rs.getLong("id"), rs.getString("name"), rs.getString("sha256"), false,
                        rs.getInt("records"), rs.getInt("occurrences"), rs.getInt("new_events"),
                        List.of(json.readValue(rs.getString("errors"), Cnab240.LineError[].class))))
                .single();
    }

    /**
     * Conciliação do arquivo: Σ pago nas liquidações do arquivo × Σ lançado em banco:boleto:liquidar para elas.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> reconcile(long id) {
        var file = find(id);
        var totals = jdbc.sql("""
                        select coalesce(sum(r.amount), 0) as arquivo,
                               coalesce(sum((select sum(l.debit - l.credit) from ledger_entry l
                                             where l.ref = 'BOL:' || r.event_key and l.account = 'banco:boleto:liquidar')), 0) as ledger,
                               count(*) filter (where i.status = 'PENDING') as pendentes,
                               count(*) filter (where i.status = 'QUARANTINED') as quarentena
                        from return_file_record r
                        left join webhook_inbox i on i.source = 'BOLETO' and i.event_key = r.event_key
                        where r.file_id = ? and r.code = '06'""")
                .param(id)
                .query((rs, n) -> new Object[] {rs.getBigDecimal("arquivo"), rs.getBigDecimal("ledger"),
                        rs.getLong("pendentes"), rs.getLong("quarentena")})
                .single();
        var result = new LinkedHashMap<String, Object>();
        result.put("arquivo", file);
        result.put("totalLiquidadoArquivo", ((BigDecimal) totals[0]).toPlainString());
        result.put("totalLancadoLedger", ((BigDecimal) totals[1]).toPlainString());
        result.put("pendentes", totals[2]);
        result.put("quarentena", totals[3]);
        result.put("fechado", ((BigDecimal) totals[0]).compareTo((BigDecimal) totals[1]) == 0 && (long) totals[2] == 0);
        return result;
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
