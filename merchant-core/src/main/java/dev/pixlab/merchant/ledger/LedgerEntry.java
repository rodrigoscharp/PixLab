package dev.pixlab.merchant.ledger;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID txId;
    private String ref;
    private String account;
    private BigDecimal debit;
    private BigDecimal credit;
    private Instant createdAt;

    protected LedgerEntry() {}

    LedgerEntry(UUID txId, String ref, Posting posting, Instant createdAt) {
        this.txId = txId;
        this.ref = ref;
        this.account = posting.account().code();
        this.debit = posting.debit();
        this.credit = posting.credit();
        this.createdAt = createdAt;
    }
}
