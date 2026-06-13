package tech.treasury.policy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.treasury.policy.DenialReason.*;

class PolicyEngineTest {

    private static final String USDC = "0x5425890298aed601595a70AB815c96711a31Bc65";
    private static final String MERCHANT = "0x6f409644a8a0b598284e8ca1a7562759f2189fbf";

    private final PolicyEngine engine = new PolicyEngine();

    /** Baseline policy: 0.50 cap, 5.00 daily, 5/min, USDC + one merchant, min reputation 60. */
    private static AgentPolicy policy() {
        return new AgentPolicy(
                500_000,          // 0.50 USDC per tx
                5_000_000,        // 5.00 USDC daily
                5,
                Set.of(MERCHANT),
                Set.of(USDC),
                60);
    }

    /** A payment that clears every rule: 0.10 USDC, nothing spent yet, high reputation. */
    private static PaymentContext goodContext() {
        return new PaymentContext("agent-1", MERCHANT, USDC, 100_000, 0, 0, 85);
    }

    @Test
    void allowsAFullyCompliantPayment() {
        assertThat(engine.evaluate(goodContext(), policy()).allowed()).isTrue();
    }

    @Test
    void addressMatchingIsCaseInsensitive() {
        PaymentContext ctx = new PaymentContext(
                "agent-1", MERCHANT.toUpperCase(), USDC.toUpperCase(), 100_000, 0, 0, 85);
        assertThat(engine.evaluate(ctx, policy()).allowed()).isTrue();
    }

    @Nested
    class Denials {

        @Test
        void deniesDisallowedAsset() {
            PaymentContext ctx = new PaymentContext(
                    "agent-1", MERCHANT, "0xdeadbeef", 100_000, 0, 0, 85);
            assertThat(engine.evaluate(ctx, policy()).reason()).isEqualTo(ASSET_NOT_ALLOWED);
        }

        @Test
        void deniesDisallowedMerchant() {
            PaymentContext ctx = new PaymentContext(
                    "agent-1", "0xstranger", USDC, 100_000, 0, 0, 85);
            assertThat(engine.evaluate(ctx, policy()).reason()).isEqualTo(MERCHANT_NOT_ALLOWED);
        }

        @Test
        void deniesUnknownCounterparty() {
            PaymentContext ctx = new PaymentContext(
                    "agent-1", MERCHANT, USDC, 100_000, 0, 0, null);
            assertThat(engine.evaluate(ctx, policy()).reason()).isEqualTo(COUNTERPARTY_UNKNOWN);
        }

        @Test
        void deniesReputationBelowFloor() {
            PaymentContext ctx = new PaymentContext(
                    "agent-1", MERCHANT, USDC, 100_000, 0, 0, 59);
            assertThat(engine.evaluate(ctx, policy()).reason()).isEqualTo(REPUTATION_BELOW_THRESHOLD);
        }

        @Test
        void deniesOverPerTxCap() {
            PaymentContext ctx = new PaymentContext(
                    "agent-1", MERCHANT, USDC, 600_000, 0, 0, 85); // 0.60 > 0.50 cap
            assertThat(engine.evaluate(ctx, policy()).reason()).isEqualTo(PER_TX_CAP_EXCEEDED);
        }

        @Test
        void deniesWhenDailyBudgetWouldBeExceeded() {
            // already spent 4.80, this 0.30 would push past the 5.00 daily budget
            PaymentContext ctx = new PaymentContext(
                    "agent-1", MERCHANT, USDC, 300_000, 4_800_000, 0, 85);
            assertThat(engine.evaluate(ctx, policy()).reason()).isEqualTo(DAILY_BUDGET_EXHAUSTED);
        }

        @Test
        void deniesAtVelocityLimit() {
            PaymentContext ctx = new PaymentContext(
                    "agent-1", MERCHANT, USDC, 100_000, 0, 5, 85); // 5 already == limit
            assertThat(engine.evaluate(ctx, policy()).reason()).isEqualTo(VELOCITY_LIMIT_EXCEEDED);
        }
    }

    @Nested
    class ReputationTiers {

        @Test
        void reducedTrustShrinksThePerTxCapToOneQuarter() {
            // reputation 70 (>= floor 60, < full-trust 80) → 25% of 0.50 cap = 0.125
            PaymentContext justOver = new PaymentContext(
                    "agent-1", MERCHANT, USDC, 130_000, 0, 0, 70); // 0.13 > 0.125
            assertThat(engine.evaluate(justOver, policy()).reason()).isEqualTo(PER_TX_CAP_EXCEEDED);

            PaymentContext justUnder = new PaymentContext(
                    "agent-1", MERCHANT, USDC, 120_000, 0, 0, 70); // 0.12 <= 0.125
            assertThat(engine.evaluate(justUnder, policy()).allowed()).isTrue();
        }

        @Test
        void reducedTrustShrinksTheDailyBudget() {
            // 25% of 5.00 = 1.25 daily budget at reputation 70
            PaymentContext overReduced = new PaymentContext(
                    "agent-1", MERCHANT, USDC, 100_000, 1_200_000, 0, 70); // 1.20 + 0.10 > 1.25
            assertThat(engine.evaluate(overReduced, policy()).reason()).isEqualTo(DAILY_BUDGET_EXHAUSTED);
        }

        @Test
        void fullTrustGetsFullLimits() {
            // reputation 80 → full 0.50 cap; 0.50 exactly is allowed
            PaymentContext ctx = new PaymentContext(
                    "agent-1", MERCHANT, USDC, 500_000, 0, 0, 80);
            assertThat(engine.evaluate(ctx, policy()).allowed()).isTrue();
        }
    }

    @Test
    void checksAreOrdered_assetBeforeReputation() {
        // disallowed asset AND unknown reputation → asset wins (checked first)
        PaymentContext ctx = new PaymentContext(
                "agent-1", MERCHANT, "0xdeadbeef", 100_000, 0, 0, null);
        assertThat(engine.evaluate(ctx, policy()).reason()).isEqualTo(ASSET_NOT_ALLOWED);
    }
}
