package dev.christopherbell.notification.delivery;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated deduplication and per-actor delivery limits. */
@Validated
@ConfigurationProperties("app.notification-delivery")
public record NotificationDeliveryProperties(
    @NotNull @DurationMin(seconds = 1) Duration dedupeWindow,
    @NotNull @DurationMin(seconds = 1) Duration rateWindow,
    @Min(1) int maxEventsPerWindow) {

  public NotificationDeliveryProperties {
    if (dedupeWindow == null || dedupeWindow.isZero() || dedupeWindow.isNegative()) {
      throw new IllegalArgumentException("Notification dedupe window must be positive.");
    }
    if (rateWindow == null || rateWindow.isZero() || rateWindow.isNegative()) {
      throw new IllegalArgumentException("Notification rate window must be positive.");
    }
    if (maxEventsPerWindow < 1) {
      throw new IllegalArgumentException("Notification rate limit must be positive.");
    }
  }
}
