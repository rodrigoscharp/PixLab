package dev.pixlab.merchant.recon;

import static dev.pixlab.merchant.ledger.Posting.credit;
import static dev.pixlab.merchant.ledger.Posting.debit;
import static java.time.ZoneOffset.UTC;

import dev.pixlab.contracts.pix.Devolucao;
import dev.pixlab.contracts.pix.Pix;
import dev.pixlab.contracts.pix.Valor;
import dev.pixlab.merchant.inbox.Inbox;
import dev.pixlab.merchant.ledger.Account;
import dev.pixlab.merchant.ledger.Ledger;
import dev.pixlab.merchant.outbox.Outbox;
import dev.pixlab.merchant.payment.PixEvents;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Classifica cada Pix do extrato contra o ledger e aplica a ação correspondente. */
@Component
class Reconciler {

    record PaymentRow(BigDecimal amount, Long chargeId, String creditAccount) {}

    private final JdbcClient jdbc;
    private final Inbox inbox;
    private final Ledger ledger;
    private final Outbox outbox;
    private final JsonMapper json;

    Reconciler(JdbcClient jdbc, Inbox inbox, Ledger ledger, Outbox outbox, JsonMapper json) {
        this.jdbc = jdbc;
        this.inbox = inbox;
        this.ledger = ledger;
        this.outbox = outbox;
        this.json = json;
    }

    ReconItem classify(Pix pix) {
        var e2eId = pix.endToEndId();
        var valor = Valor.parse(pix.valor());
        var pspNet = valor.subtract(refunded(pix));
        var ledgerNet = liquidarNet(e2eId);
        var creditNet = jdbc.sql("""
                        select coalesce(sum(debit - credit), 0) from ledger_entry
                        where account = 'psp:pix:liquidar' and ref in (:e2e, :adj)""")
                .param("e2e", e2eId).param("adj", adjustmentRef(e2eId)).query(BigDecimal.class).single();
        var payment = jdbc.sql("""
                        select p.amount, p.charge_id,
                               (select account from ledger_entry where ref = p.e2e_id and credit > 0 limit 1) as credit_account
                        from payment p where p.e2e_id = ?""")
                .param(e2eId)
                .query((rs, n) -> new PaymentRow(rs.getBigDecimal("amount"), (Long) rs.getObject("charge_id"),
                        rs.getString("credit_account")))
                .optional().orElse(null);

        if (payment == null) {
            return new ReconItem(e2eId, ReconResult.FALTA_NO_LEDGER, pix, pspNet, ledgerNet, null,
                    "reinjetado na inbox");
        }
        if (creditNet.compareTo(valor) != 0) {
            return new ReconItem(e2eId, ReconResult.VALOR_DIVERGENTE, pix, pspNet, ledgerNet,
                    valor.subtract(creditNet), "ledger " + creditNet + " ≠ PSP " + valor + "; ajuste lançado");
        }
        if (payment.amount().compareTo(valor) != 0) {
            return new ReconItem(e2eId, ReconResult.VALOR_DIVERGENTE, pix, pspNet, ledgerNet, null,
                    "já ajustado; caso aberto para análise");
        }
        if (payment.chargeId() == null) {
            return new ReconItem(e2eId, ReconResult.SEM_COBRANCA, pix, pspNet, ledgerNet, null, "em suspense");
        }
        if (Account.CREDITO_A_DEVOLVER.code().equals(payment.creditAccount())) {
            return new ReconItem(e2eId, ReconResult.DUPLICADO, pix, pspNet, ledgerNet, null, "crédito a devolver");
        }
        return new ReconItem(e2eId, ReconResult.OK, pix, pspNet, ledgerNet, null, null);
    }

    /** Escreve o item e executa a ação, na transação do chunk. Itens já gravados (restart) são ignorados. */
    @Transactional(propagation = Propagation.MANDATORY)
    void write(long runId, ReconItem item) {
        int inserted = jdbc.sql("""
                        insert into recon_item (run_id, key, result, psp_amount, ledger_amount, action, details)
                        values (:run, :key, :result, :psp, :ledger, :action, cast(:details as jsonb))
                        on conflict (run_id, key) do nothing""")
                .param("run", runId)
                .param("key", item.key())
                .param("result", item.result().name())
                .param("psp", item.pspAmount())
                .param("ledger", item.ledgerAmount())
                .param("action", action(item))
                .param("details", json.writeValueAsString(item.detail() == null ? Map.of() : Map.of("detail", item.detail())))
                .update();
        if (inserted == 0 || item.pix() == null) {
            return;
        }
        // Sempre repassa o Pix completo pela inbox: idempotente, e repara devoluções cujo aviso se perdeu.
        PixEvents.offer(inbox, json, item.pix(), true);
        if (item.adjustment() != null && item.adjustment().signum() != 0) {
            var amount = item.adjustment().abs();
            var postings = item.adjustment().signum() > 0
                    ? List.of(debit(Account.PSP_PIX_LIQUIDAR, amount), credit(Account.SUSPENSE_NAO_IDENTIFICADO, amount))
                    : List.of(debit(Account.SUSPENSE_NAO_IDENTIFICADO, amount), credit(Account.PSP_PIX_LIQUIDAR, amount));
            ledger.post(adjustmentRef(item.key()), postings);
            outbox.add("recon", item.key(), "recon.valor_divergente", Map.of(
                    "endToEndId", item.key(), "ajuste", item.adjustment().toPlainString()));
        }
    }

    /** Ledger tem pagamento na janela que o PSP não listou: possível crédito fantasma. */
    @Transactional(propagation = Propagation.MANDATORY)
    int markMissingInPsp(long runId, Instant inicio, Instant fim) {
        return jdbc.sql("""
                        insert into recon_item (run_id, key, result, psp_amount, ledger_amount, action, details)
                        select :run, p.e2e_id, 'FALTA_NO_PSP', null,
                               (select coalesce(sum(debit - credit), 0) from ledger_entry l
                                where l.account = 'psp:pix:liquidar'
                                  and (l.ref = p.e2e_id or l.ref like p.e2e_id || ':%' or l.ref = 'recon:' || p.e2e_id)),
                               'ALERTA', '{"detail":"pagamento sem Pix correspondente no extrato do PSP"}'::jsonb
                        from payment p
                        where p.paid_at >= :inicio and p.paid_at < :fim
                          and not exists (select 1 from recon_item i where i.run_id = :run and i.key = p.e2e_id)
                        on conflict (run_id, key) do nothing""")
                .param("run", runId)
                .param("inicio", inicio.atOffset(UTC))
                .param("fim", fim.atOffset(UTC))
                .update();
    }

    BigDecimal liquidarNet(String e2eId) {
        return jdbc.sql("""
                        select coalesce(sum(debit - credit), 0) from ledger_entry
                        where account = 'psp:pix:liquidar' and (ref = :e2e or ref like :refunds or ref = :adj)""")
                .param("e2e", e2eId).param("refunds", e2eId + ":%").param("adj", adjustmentRef(e2eId))
                .query(BigDecimal.class).single();
    }

    static String adjustmentRef(String e2eId) {
        return "recon:" + e2eId;
    }

    private static BigDecimal refunded(Pix pix) {
        if (pix.devolucoes() == null) {
            return BigDecimal.ZERO;
        }
        return pix.devolucoes().stream().filter(d -> Devolucao.DEVOLVIDO.equals(d.status()))
                .map(d -> Valor.parse(d.valor())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String action(ReconItem item) {
        return switch (item.result()) {
            case FALTA_NO_LEDGER -> "REINJETAR";
            case VALOR_DIVERGENTE -> item.adjustment() != null ? "AJUSTAR" : "CASO";
            case FALTA_NO_PSP -> "ALERTA";
            case DUPLICADO -> "DEVOLVER";
            case SEM_COBRANCA -> "SUSPENSE";
            case OK -> null;
        };
    }
}
