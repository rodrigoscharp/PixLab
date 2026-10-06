package dev.pixlab.merchant.recon;

import static java.time.ZoneOffset.UTC;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@code recon_run} e {@code recon_item}. */
@Repository
public class ReconRuns {

    public record Run(long id, String windowStart, String windowEnd, String status, Long jobExecutionId,
            Map<String, Object> summary) {}

    public record Item(String key, String result, BigDecimal pspAmount, BigDecimal ledgerAmount, String action) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ReconRuns(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    long create(Instant inicio, Instant fim) {
        return jdbc.sql("insert into recon_run (window_start, window_end, status) values (:s, :e, 'STARTING') returning id")
                .param("s", inicio.atOffset(UTC)).param("e", fim.atOffset(UTC)).query(Long.class).single();
    }

    void update(long id, String status, Long jobExecutionId, boolean finished) {
        jdbc.sql("""
                        update recon_run set status = :status, job_execution_id = :exec,
                            finished_at = case when :finished then now() else finished_at end
                        where id = :id""")
                .param("status", status).param("exec", jobExecutionId).param("finished", finished).param("id", id)
                .update();
    }

    /**
     * Resumo da execução: contagem por resultado e fechamento (invariante 4) dos e2eIds do extrato. Itens
     * reinjetados ainda estão a caminho do ledger; a próxima execução confirma que fecharam.
     */
    void summarize(long runId) {
        var counts = new LinkedHashMap<String, Object>();
        for (var result : ReconResult.values()) {
            counts.put(result.name(), 0L);
        }
        jdbc.sql("select result, count(*) as n from recon_item where run_id = ? group by result")
                .param(runId)
                .query((rs, n) -> Map.entry(rs.getString("result"), rs.getLong("n")))
                .list().forEach(e -> counts.put(e.getKey(), e.getValue()));
        var totals = jdbc.sql("""
                        select coalesce(sum(psp_amount), 0) as psp, coalesce(sum(ledger_amount), 0) as ledger
                        from recon_item where run_id = ? and result <> 'FALTA_NO_PSP'""")
                .param(runId)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("psp"), rs.getBigDecimal("ledger")})
                .single();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("counts", counts);
        summary.put("pspTotal", totals[0].toPlainString());
        summary.put("ledgerTotal", totals[1].toPlainString());
        summary.put("fechado", totals[0].compareTo(totals[1]) == 0 && (long) counts.get("FALTA_NO_PSP") == 0);
        jdbc.sql("update recon_run set summary = cast(:summary as jsonb) where id = :id")
                .param("summary", json.writeValueAsString(summary)).param("id", runId).update();
    }

    @SuppressWarnings("unchecked")
    public Optional<Run> find(long id) {
        return jdbc.sql("select id, window_start, window_end, status, job_execution_id, summary::text as summary from recon_run where id = ?")
                .param(id)
                .query((rs, n) -> new Run(rs.getLong("id"),
                        rs.getObject("window_start", OffsetDateTime.class).toInstant().toString(),
                        rs.getObject("window_end", OffsetDateTime.class).toInstant().toString(),
                        rs.getString("status"), (Long) rs.getObject("job_execution_id"),
                        rs.getString("summary") == null ? Map.of() : json.readValue(rs.getString("summary"), Map.class)))
                .optional();
    }

    public List<Item> items(long runId) {
        return jdbc.sql("select key, result, psp_amount, ledger_amount, action from recon_item where run_id = ? order by id")
                .param(runId)
                .query((rs, n) -> new Item(rs.getString("key"), rs.getString("result"), rs.getBigDecimal("psp_amount"),
                        rs.getBigDecimal("ledger_amount"), rs.getString("action")))
                .list();
    }
}
