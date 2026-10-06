package dev.pixlab.merchant.admin;

import dev.pixlab.merchant.inbox.Inbox;
import dev.pixlab.merchant.inbox.InboxStatus;
import dev.pixlab.merchant.payment.ConsultaAtiva;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operação do laboratório: estado da inbox e consulta ativa sob demanda. */
@RestController
@RequestMapping("/admin")
class AdminController {

    record ConsultaRequest(Instant inicio, Instant fim) {}

    private final Inbox inbox;
    private final ConsultaAtiva consulta;

    private final JdbcClient jdbc;

    AdminController(Inbox inbox, ConsultaAtiva consulta, JdbcClient jdbc) {
        this.inbox = inbox;
        this.consulta = consulta;
        this.jdbc = jdbc;
    }

    @GetMapping("/inbox")
    Map<String, Long> inbox() {
        var counts = new LinkedHashMap<String, Long>();
        for (var status : InboxStatus.values()) {
            counts.put(status.name(), inbox.countByStatus(status));
        }
        return counts;
    }

    record LedgerLine(String account, String debit, String credit) {}

    record PaymentView(String endToEndId, String txid, String valor, List<LedgerLine> ledger) {}

    /** Pagamento e lançamentos de um e2eId; usado pelos testes de caos para provar crédito único. */
    @GetMapping("/payments/{e2eId}")
    List<PaymentView> payment(@PathVariable String e2eId) {
        var ledger = jdbc.sql("select account, debit, credit from ledger_entry where ref = ? order by id")
                .param(e2eId)
                .query((rs, n) -> new LedgerLine(rs.getString("account"), rs.getBigDecimal("debit").toPlainString(),
                        rs.getBigDecimal("credit").toPlainString()))
                .list();
        return jdbc.sql("""
                        select p.e2e_id, c.txid, p.amount from payment p left join charge c on c.id = p.charge_id
                        where p.e2e_id = ?""")
                .param(e2eId)
                .query((rs, n) -> new PaymentView(rs.getString("e2e_id"), rs.getString("txid"),
                        rs.getBigDecimal("amount").toPlainString(), ledger))
                .list();
    }

    /** Saldo líquido de devoluções de um Pix no ledger e o estado de cada devolução. */
    @GetMapping("/refunds/{e2eId}")
    Map<String, String> refunds(@PathVariable String e2eId) {
        var result = new LinkedHashMap<String, String>();
        result.put("devolucoes", jdbc.sql("""
                        select coalesce(sum(debit - credit), 0) from ledger_entry
                        where ref like ? and account = 'merchant:devolucoes'""")
                .param(e2eId + ":%").query(BigDecimal.class).single().setScale(2).toPlainString());
        jdbc.sql("""
                        select r.refund_id, r.status from refund r join payment p on p.id = r.payment_id
                        where p.e2e_id = ? order by r.id""")
                .param(e2eId)
                .query((rs, n) -> Map.entry(rs.getString("refund_id"), rs.getString("status")))
                .list().forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    @PostMapping("/consulta-ativa")
    ConsultaAtiva.Result consultaAtiva(@RequestBody ConsultaRequest request) {
        return consulta.run(request.inicio(), request.fim());
    }
}
