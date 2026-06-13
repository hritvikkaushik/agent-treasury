package tech.treasury.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tech.treasury.domain.PaymentIntent;

/**
 * Stand-in that "settles" instantly with a deterministic fake tx hash. Active by default; replaced by
 * {@link X402PaymentExecutor} when {@code x402.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "x402.enabled", havingValue = "false", matchIfMissing = true)
public class StubPaymentExecutor implements PaymentExecutor {

    @Override
    public ExecutionResult execute(PaymentIntent intent) {
        String fakeTx = "0xstub" + intent.getId().toString().replace("-", "");
        return ExecutionResult.ok(fakeTx);
    }
}
