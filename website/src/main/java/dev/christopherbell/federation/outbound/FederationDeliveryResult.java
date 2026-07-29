package dev.christopherbell.federation.outbound;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Bounded outcome from one remote delivery attempt; remote response bodies are never retained. */
sealed interface FederationDeliveryResult {
  record Delivered(int statusCode) implements FederationDeliveryResult {
    public Delivered {
      requireStatus(statusCode);
    }
  }

  record RetryableFailure(
      OptionalInt statusCode,
      Optional<Duration> retryAfter
  ) implements FederationDeliveryResult {
    public RetryableFailure {
      Objects.requireNonNull(statusCode, "statusCode");
      retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
      statusCode.ifPresent(FederationDeliveryResult::requireStatus);
      retryAfter.ifPresent(duration -> {
        if (duration.isNegative()) {
          throw new IllegalArgumentException("Federation retry delay cannot be negative");
        }
      });
    }
  }

  record PermanentFailure(int statusCode) implements FederationDeliveryResult {
    public PermanentFailure {
      requireStatus(statusCode);
    }
  }

  private static void requireStatus(int statusCode) {
    if (statusCode < 100 || statusCode > 599) {
      throw new IllegalArgumentException("Federation delivery status must be an HTTP status code");
    }
  }
}
