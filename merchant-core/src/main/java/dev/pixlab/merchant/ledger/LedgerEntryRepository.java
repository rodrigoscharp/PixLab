package dev.pixlab.merchant.ledger;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    @Query("""
            select new dev.pixlab.merchant.ledger.AccountBalance(e.account, sum(e.debit), sum(e.credit))
            from LedgerEntry e group by e.account order by e.account""")
    List<AccountBalance> balances();
}
