package dev.christopherbell.notification.preference;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Stores per-account notification category opt-ins. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("notification_preferences")
public class NotificationPreference {
  @Id private String id;
  @Indexed(unique = true) private String accountId;
  private boolean mentions;
  private boolean likes;
  private boolean comments;
  private boolean messages;
  private boolean wflSessions;
  @CreatedDate private Instant createdOn;
  @LastModifiedDate private Instant lastUpdatedOn;
}
