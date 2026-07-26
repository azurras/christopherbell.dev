package dev.christopherbell.post.editing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated edit-window and revision-history limits for posts and replies. */
@Validated
@ConfigurationProperties("posts")
public record PostEditingProperties(
    @NotNull @DurationMin(seconds = 1) Duration editWindow,
    @Min(1) @Max(100) int editAuditLimit) {

  public PostEditingProperties {
    if (editWindow == null || editWindow.isZero() || editWindow.isNegative()) {
      throw new IllegalArgumentException("Post edit window must be positive.");
    }
    if (editAuditLimit < 1 || editAuditLimit > 100) {
      throw new IllegalArgumentException("Post edit audit limit must be between 1 and 100.");
    }
  }
}
