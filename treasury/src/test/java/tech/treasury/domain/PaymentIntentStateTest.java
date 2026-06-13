package tech.treasury.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.treasury.domain.PaymentIntentState.*;

class PaymentIntentStateTest {

    @Test
    void happyPathTransitionsAreAllowed() {
        assertThat(REQUESTED.canTransitionTo(APPROVED)).isTrue();
        assertThat(APPROVED.canTransitionTo(SIGNED)).isTrue();
        assertThat(SIGNED.canTransitionTo(SETTLED)).isTrue();
    }

    @Test
    void denialAndFailurePathsAreAllowed() {
        assertThat(REQUESTED.canTransitionTo(DENIED)).isTrue();
        assertThat(APPROVED.canTransitionTo(DENIED)).isTrue();
        assertThat(SIGNED.canTransitionTo(FAILED)).isTrue();
        assertThat(FAILED.canTransitionTo(SETTLED)).isTrue(); // reconciliation
    }

    @Test
    void illegalTransitionsAreRejected() {
        assertThat(REQUESTED.canTransitionTo(SIGNED)).isFalse();   // must be approved first
        assertThat(REQUESTED.canTransitionTo(SETTLED)).isFalse();
        assertThat(APPROVED.canTransitionTo(SETTLED)).isFalse();   // must be signed first
        assertThat(SETTLED.canTransitionTo(FAILED)).isFalse();     // terminal
        assertThat(DENIED.canTransitionTo(APPROVED)).isFalse();    // terminal
    }

    @Test
    void terminalStatesAreFlagged() {
        assertThat(SETTLED.isTerminal()).isTrue();
        assertThat(DENIED.isTerminal()).isTrue();
        assertThat(REQUESTED.isTerminal()).isFalse();
        assertThat(SIGNED.isTerminal()).isFalse();
    }
}
