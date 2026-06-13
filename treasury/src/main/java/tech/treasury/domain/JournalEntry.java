package tech.treasury.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One side of a double-entry ledger posting. Rows sharing an intent must net to zero. */
@Entity
@Table(name = "journal_entry")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "intent_id")
    private UUID intentId;

    private String account;

    @Column(name = "debit_atomic")
    private long debitAtomic;

    @Column(name = "credit_atomic")
    private long creditAtomic;

    @Column(name = "created_at")
    private Instant createdAt;

    protected JournalEntry() {
    }

    private JournalEntry(UUID intentId, String account, long debitAtomic, long creditAtomic, Instant now) {
        this.intentId = intentId;
        this.account = account;
        this.debitAtomic = debitAtomic;
        this.creditAtomic = creditAtomic;
        this.createdAt = now;
    }

    public static JournalEntry debit(UUID intentId, String account, long amount, Instant now) {
        return new JournalEntry(intentId, account, amount, 0, now);
    }

    public static JournalEntry credit(UUID intentId, String account, long amount, Instant now) {
        return new JournalEntry(intentId, account, 0, amount, now);
    }

    public String getAccount() {
        return account;
    }

    public long getDebitAtomic() {
        return debitAtomic;
    }

    public long getCreditAtomic() {
        return creditAtomic;
    }
}
