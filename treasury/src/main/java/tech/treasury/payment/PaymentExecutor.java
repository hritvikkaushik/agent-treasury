package tech.treasury.payment;

import tech.treasury.domain.PaymentIntent;

/**
 * Executes an approved payment (sign + settle). Phase 1 uses a stub; Phase 2 swaps in the real x402
 * client (web3j EIP-3009 sign -> facilitator /settle) — proven in the smoke test.
 */
public interface PaymentExecutor {

    ExecutionResult execute(PaymentIntent intent);

    record ExecutionResult(boolean success, String txHash, String error) {

        public static ExecutionResult ok(String txHash) {
            return new ExecutionResult(true, txHash, null);
        }

        public static ExecutionResult failed(String error) {
            return new ExecutionResult(false, null, error);
        }
    }
}
