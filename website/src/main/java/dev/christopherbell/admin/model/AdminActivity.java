package dev.christopherbell.admin.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@Document("admin_activity")
public class AdminActivity {
  @Id private String id;
  private String actorAccountId;
  private String actorUsername;
  private String action;
  private String targetType;
  private String targetId;
  private String targetLabel;
  private String message;
  private Map<String, String> metadata;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  private Instant createdOn;
}
