package dev.christopherbell.notification.delivery;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Short-lived unique claim preventing duplicate fanout inserts. */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@Document("notification_delivery_guards")
public class NotificationDeliveryGuard {
  @Id private String id;
  private String accountId;
  private String actorAccountId;
  private String notificationType;
  private String targetId;
  @Indexed(expireAfter = "0s") private Instant expiresAt;
}
