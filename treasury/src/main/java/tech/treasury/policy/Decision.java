package tech.treasury.policy;

/** Outcome of policy evaluation. {@code reason} is null when {@code allowed} is true. */
public record Decision(boolean allowed, DenialReason reason) {

    public static Decision allow() {
        return new Decision(true, null);
    }

    public static Decision deny(DenialReason reason) {
        return new Decision(false, reason);
    }
}
