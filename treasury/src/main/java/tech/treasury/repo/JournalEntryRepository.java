package tech.treasury.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tech.treasury.domain.JournalEntry;

import java.time.Instant;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    @Query("select coalesce(sum(j.debitAtomic), 0) from JournalEntry j "
            + "where j.account = :account and j.createdAt >= :since")
    long sumDebitsSince(@Param("account") String account, @Param("since") Instant since);
}
