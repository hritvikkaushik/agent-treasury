package tech.treasury.reputation;

import org.springframework.stereotype.Component;

/** Default no-op feedback writer (offline / tests). Replaced by {@link Erc8004FeedbackWriter} when enabled. */
@Component
public class NoOpFeedbackWriter implements FeedbackWriter {

    @Override
    public void recordSuccessfulPayment(String payee) {
        // no-op
    }
}
