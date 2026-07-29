package dev.christopherbell.post.abuse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated new-account Void mutation budgets. */
@Validated
@ConfigurationProperties("posts.new-account-limits")
public record NewAccountVoidMutationLimitProperties(
    @NotNull @DurationMin(seconds = 1) Duration accountAge,
    @NotNull @DurationMin(seconds = 1) Duration window,
    @Min(1) @Max(10_000) int maxTrackedKeys,
    @Min(1) @Max(10_000) int rootPostCapacity,
    @Min(1) @Max(10_000) int replyCapacity,
    @Min(1) @Max(10_000) int keepAliveCapacity,
    @Min(1) @Max(10_000) int followCapacity
) {
  public NewAccountVoidMutationLimitProperties {
    if (accountAge == null || accountAge.isZero() || accountAge.isNegative()
        || window == null || window.isZero() || window.isNegative()
        || maxTrackedKeys < 1 || maxTrackedKeys > 10_000
        || rootPostCapacity < 1 || replyCapacity < 1
        || keepAliveCapacity < 1 || followCapacity < 1
        || rootPostCapacity > 10_000 || replyCapacity > 10_000
        || keepAliveCapacity > 10_000 || followCapacity > 10_000) {
      throw new IllegalArgumentException("Invalid new-account Void mutation limits.");
    }
  }

  public int capacity(VoidMutationKind kind) {
    return switch (kind) {
      case ROOT_POST -> rootPostCapacity;
      case REPLY -> replyCapacity;
      case KEEP_ALIVE -> keepAliveCapacity;
      case FOLLOW -> followCapacity;
    };
  }
}
