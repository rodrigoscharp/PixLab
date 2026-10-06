package dev.pixlab.psp.devolucao;

import static java.time.ZoneOffset.UTC;

import dev.pixlab.contracts.pix.Devolucao;
import dev.pixlab.contracts.pix.Valor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DevolucaoRepository {

    public record Pendente(String e2eId, String id, String rtrId) {}

    private final JdbcClient jdbc;

    DevolucaoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(String e2eId, Devolucao d, Instant solicitacao, Instant resolveAt) {
        jdbc.sql("""
                        insert into devolucao (e2e_id, id, rtr_id, valor, natureza, status, motivo, solicitacao, resolve_at)
                        values (:e2e, :id, :rtr, :valor, :natureza, :status, :motivo, :solicitacao, :resolve)""")
                .param("e2e", e2eId)
                .param("id", d.id())
                .param("rtr", d.rtrId())
                .param("valor", Valor.parse(d.valor()))
                .param("natureza", d.natureza())
                .param("status", d.status())
                .param("motivo", d.motivo())
                .param("solicitacao", solicitacao.atOffset(UTC))
                .param("resolve", resolveAt == null ? null : resolveAt.atOffset(UTC))
                .update();
    }

    public Optional<Devolucao> find(String e2eId, String id) {
        return jdbc.sql("select * from devolucao where e2e_id = :e2e and id = :id")
                .param("e2e", e2eId).param("id", id).query(DevolucaoRepository::map).optional();
    }

    public List<Devolucao> byE2eId(String e2eId) {
        return byE2eIds(List.of(e2eId)).getOrDefault(e2eId, List.of());
    }

    public Map<String, List<Devolucao>> byE2eIds(Collection<String> e2eIds) {
        if (e2eIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("select * from devolucao where e2e_id in (:ids) order by solicitacao, id")
                .param("ids", e2eIds)
                .query((rs, n) -> Map.entry(rs.getString("e2e_id"), map(rs, n)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    boolean rtrIdExists(String rtrId) {
        return jdbc.sql("select exists(select 1 from devolucao where rtr_id = ?)").param(rtrId)
                .query(Boolean.class).single();
    }

    /** Σ das devoluções que não falharam: o limite do invariante 3 é contra esse valor. */
    BigDecimal comprometido(String e2eId) {
        return jdbc.sql("select coalesce(sum(valor), 0) from devolucao where e2e_id = :e2e and status <> 'NAO_REALIZADO'")
                .param("e2e", e2eId).query(BigDecimal.class).single();
    }

    List<Pendente> claimDue(Instant now) {
        return jdbc.sql("""
                        select e2e_id, id, rtr_id from devolucao
                        where status = 'EM_PROCESSAMENTO' and resolve_at <= :now
                        order by resolve_at limit 50
                        for update skip locked""")
                .param("now", now.atOffset(UTC))
                .query((rs, n) -> new Pendente(rs.getString("e2e_id"), rs.getString("id"), rs.getString("rtr_id")))
                .list();
    }

    void resolve(String e2eId, String id, String status, String motivo) {
        jdbc.sql("update devolucao set status = :status, motivo = :motivo, resolve_at = null where e2e_id = :e2e and id = :id")
                .param("status", status).param("motivo", motivo).param("e2e", e2eId).param("id", id).update();
    }

    private static Devolucao map(ResultSet rs, int n) throws SQLException {
        return new Devolucao(rs.getString("id"), rs.getString("rtr_id"), Valor.format(rs.getBigDecimal("valor")),
                rs.getString("natureza"), rs.getString("status"),
                rs.getObject("solicitacao", OffsetDateTime.class).toInstant().toString(), rs.getString("motivo"));
    }
}
