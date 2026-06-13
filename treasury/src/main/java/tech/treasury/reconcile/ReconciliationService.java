package tech.treasury.reconcile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tech.treasury.domain.PaymentIntent;
import tech.treasury.domain.PaymentIntentState;
import tech.treasury.repo.PaymentIntentRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Defense-in-depth: periodically re-verify that intents we marked SETTLED actually have a confirmed
 * transaction on-chain, and flag any mismatch. We don't trust our own ledger blindly. Active only
 * with real settlement ({@code x402.enabled=true}); stub tx hashes are skipped.
 */
@Service
@ConditionalOnProperty(name = "x402.enabled", havingValue = "true")
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final int REAL_TX_HASH_LENGTH = 66; // 0x + 32 bytes

    private final PaymentIntentRepository intents;
    private final ReceiptStatusProvider receipts;

    public ReconciliationService(PaymentIntentRepository intents, ReceiptStatusProvider receipts) {
        this.intents = intents;
        this.receipts = receipts;
    }

    public record Result(int checked, int mismatched) {
    }

    @Scheduled(
            initialDelayString = "${reconciliation.initial-delay-ms:30000}",
            fixedDelayString = "${reconciliation.interval-ms:60000}")
    public void scheduled() {
        Result r = reconcile(Instant.now());
        log.info("reconciliation: {} settled checked, {} mismatched", r.checked(), r.mismatched());
    }

    /** Audit recent SETTLED intents against the chain. Returns a summary; logs each mismatch. */
    public Result reconcile(Instant now) {
        List<PaymentIntent> settled =
                intents.findByStateAndCreatedAtAfter(PaymentIntentState.SETTLED, now.minus(WINDOW));
        int checked = 0;
        int mismatched = 0;
        for (PaymentIntent intent : settled) {
            String tx = intent.getTxHash();
            if (tx == null || tx.length() != REAL_TX_HASH_LENGTH) {
                continue; // stub / non-onchain hash — nothing to verify
            }
            checked++;
            ReceiptStatusProvider.TxStatus status = receipts.statusOf(tx);
            if (status != ReceiptStatusProvider.TxStatus.CONFIRMED) {
                mismatched++;
                log.warn("RECONCILIATION MISMATCH: intent {} is SETTLED but tx {} is {}",
                        intent.getId(), tx, status);
            }
        }
        return new Result(checked, mismatched);
    }
}
