package tech.treasury.reconcile;

import org.junit.jupiter.api.Test;
import tech.treasury.domain.PaymentIntent;
import tech.treasury.domain.PaymentIntentState;
import tech.treasury.repo.PaymentIntentRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static tech.treasury.reconcile.ReceiptStatusProvider.TxStatus.CONFIRMED;
import static tech.treasury.reconcile.ReceiptStatusProvider.TxStatus.NOT_FOUND;

/** Pure unit test — no Spring, no chain. The receipt provider is a fake map. */
class ReconciliationServiceTest {

    private static final String REAL_TX = "0x" + "a".repeat(64);   // 66 chars
    private static final String OTHER_TX = "0x" + "b".repeat(64);
    private static final String STUB_TX = "0xstubdeadbeef";        // not a real hash

    private final PaymentIntentRepository repo = mock(PaymentIntentRepository.class);

    private static PaymentIntent settled(String txHash, Instant now) {
        PaymentIntent i = new PaymentIntent(UUID.randomUUID(), "a", "0xpayee", "0xusdc", 100_000, "k-" + txHash, now);
        i.transitionTo(PaymentIntentState.APPROVED, now);
        i.transitionTo(PaymentIntentState.SIGNED, now);
        i.markSettled(txHash, now);
        return i;
    }

    private ReconciliationService serviceReturning(List<PaymentIntent> settledIntents,
                                                   Map<String, ReceiptStatusProvider.TxStatus> chain) {
        when(repo.findByStateAndCreatedAtAfter(eq(PaymentIntentState.SETTLED), any()))
                .thenReturn(settledIntents);
        ReceiptStatusProvider receipts = tx -> chain.getOrDefault(tx, NOT_FOUND);
        return new ReconciliationService(repo, receipts);
    }

    @Test
    void confirmedSettlementsAreClean() {
        Instant now = Instant.now();
        var svc = serviceReturning(
                List.of(settled(REAL_TX, now), settled(OTHER_TX, now)),
                Map.of(REAL_TX, CONFIRMED, OTHER_TX, CONFIRMED));

        ReconciliationService.Result r = svc.reconcile(now);

        assertThat(r.checked()).isEqualTo(2);
        assertThat(r.mismatched()).isZero();
    }

    @Test
    void settledButNotOnChainIsFlagged() {
        Instant now = Instant.now();
        var svc = serviceReturning(
                List.of(settled(REAL_TX, now), settled(OTHER_TX, now)),
                Map.of(REAL_TX, CONFIRMED)); // OTHER_TX missing -> NOT_FOUND

        ReconciliationService.Result r = svc.reconcile(now);

        assertThat(r.checked()).isEqualTo(2);
        assertThat(r.mismatched()).isEqualTo(1);
    }

    @Test
    void stubHashesAreSkipped() {
        Instant now = Instant.now();
        var svc = serviceReturning(List.of(settled(STUB_TX, now)), Map.of());

        ReconciliationService.Result r = svc.reconcile(now);

        assertThat(r.checked()).isZero();
        assertThat(r.mismatched()).isZero();
    }
}
