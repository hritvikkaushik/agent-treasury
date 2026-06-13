package tech.treasury.payment;

import org.springframework.stereotype.Component;
import tech.treasury.domain.PaymentIntent;

/** Phase-1 stand-in that "settles" instantly with a deterministic fake tx hash. */
@Component
public class StubPaymentExecutor implements PaymentExecutor {

    @Override
    public ExecutionResult execute(PaymentIntent intent) {
        String fakeTx = "0xstub" + intent.getId().toString().replace("-", "");
        return ExecutionResult.ok(fakeTx);
    }
}
