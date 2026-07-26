package dev.christopherbell.notification.delivery;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Fixed-window delivery counter scoped to one actor, recipient, and event type. */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@Document("notification_rate_limits")
public class NotificationRateLimit {
  @Id private String id;
  private String accountId;
  private String actorAccountId;
  private String notificationType;
  private Long count;
  @Indexed(expireAfter = "0s") private Instant expiresAt;
}
