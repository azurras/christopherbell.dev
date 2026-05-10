package dev.christopherbell.notification.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@AllArgsConstructor
@Builder
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@Document("notifications")
public class Notification {
  private final String type = "notification";

  @Id private String id;
  private String accountId;
  private String actorAccountId;
  private String actorUsername;
  private String postId;
  private String postText;
  private NotificationType notificationType;
  private Boolean read;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  @CreatedDate
  private Instant createdOn;
}
