package tech.treasury.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/** Admin request to create an agent. Monetary fields are atomic units. {@code id} is optional. */
public record CreateAgentRequest(
        String id,
        @NotBlank String name,
        @PositiveOrZero long perTxCapAtomic,
        @PositiveOrZero long dailyBudgetAtomic,
        @PositiveOrZero int velocityPerMinute,
        @Min(0) @Max(100) int minReputation,
        List<String> allowedMerchants,
        List<String> allowedAssets
) {
}
