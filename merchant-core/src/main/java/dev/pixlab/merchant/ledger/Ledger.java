package dev.pixlab.merchant.ledger;

import dev.pixlab.merchant.support.Traces;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ledger de partida dobrada: cada transação é gravada inteira e balanceada, ou não é gravada. */
@Service
public class Ledger {

    private final LedgerEntryRepository entries;
    private final Clock clock;
    private final Traces traces;

    Ledger(LedgerEntryRepository entries, Clock clock, Traces traces) {
        this.entries = entries;
        this.clock = clock;
        this.traces = traces;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(String ref, List<Posting> postings) {
        return traces.inSpan("ledger.post", null, () -> {
            requireBalanced(postings);
            var txId = UUID.randomUUID();
            var now = clock.instant();
            entries.saveAll(postings.stream().map(p -> new LedgerEntry(txId, ref, p, now)).toList());
            return txId;
        });
    }

    @Transactional(readOnly = true)
    public List<AccountBalance> balances() {
        return entries.balances();
    }

    static void requireBalanced(List<Posting> postings) {
        if (postings.size() < 2) {
            throw new IllegalArgumentException("transação precisa de ao menos dois lançamentos");
        }
        var debit = BigDecimal.ZERO;
        var credit = BigDecimal.ZERO;
        for (var p : postings) {
            if (p.debit().signum() < 0 || p.credit().signum() < 0 || (p.debit().signum() > 0) == (p.credit().signum() > 0)) {
                throw new IllegalArgumentException("lançamento deve ser débito OU crédito positivo: " + p);
            }
            debit = debit.add(p.debit());
            credit = credit.add(p.credit());
        }
        if (debit.compareTo(credit) != 0) {
            throw new IllegalArgumentException("transação desbalanceada: débito " + debit + " ≠ crédito " + credit);
        }
    }
}
