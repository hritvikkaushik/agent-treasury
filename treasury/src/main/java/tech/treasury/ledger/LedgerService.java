package tech.treasury.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.treasury.domain.JournalEntry;
import tech.treasury.repo.JournalEntryRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Double-entry ledger. A payment debits the agent's budget and credits the merchant-payable account. */
@Service
public class LedgerService {

    private final JournalEntryRepository journal;

    public LedgerService(JournalEntryRepository journal) {
        this.journal = journal;
    }

    /** Post the balanced pair for a payment. Debit and credit are equal, so the journal nets to zero. */
    @Transactional
    public void recordPayment(UUID intentId, String agentId, long amountAtomic, Instant now) {
        journal.save(JournalEntry.debit(intentId, Accounts.budget(agentId), amountAtomic, now));
        journal.save(JournalEntry.credit(intentId, Accounts.MERCHANT_PAYABLE, amountAtomic, now));
    }

    /** Sum of debits to the agent's budget account since the start of the current UTC day. */
    public long spentTodayAtomic(String agentId, Instant now) {
        Instant startOfUtcDay = now.truncatedTo(ChronoUnit.DAYS);
        return journal.sumDebitsSince(Accounts.budget(agentId), startOfUtcDay);
    }
}
