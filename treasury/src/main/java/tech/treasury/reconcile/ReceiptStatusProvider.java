package tech.treasury.reconcile;

/** Looks up the on-chain status of a transaction hash. The seam that keeps reconciliation testable. */
public interface ReceiptStatusProvider {

    enum TxStatus { CONFIRMED, FAILED, NOT_FOUND }

    TxStatus statusOf(String txHash);
}
