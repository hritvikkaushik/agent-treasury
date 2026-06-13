package tech.treasury.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** A payment an agent asks the treasury to make on its behalf. */
public record PaymentRequest(
        @NotBlank String payee,
        @NotBlank String asset,
        @NotNull @Positive Long amountAtomic
) {
}
