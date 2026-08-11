package dev.christopherbell.admin.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@CompoundIndexes({
    @CompoundIndex(name = "admin_activity_created_id_desc", def = "{'createdOn': -1, '_id': -1}"),
    @CompoundIndex(name = "admin_activity_action_created_id_desc", def = "{'action': 1, 'createdOn': -1, '_id': -1}"),
    @CompoundIndex(name = "admin_activity_target_created_id_desc", def = "{'targetType': 1, 'createdOn': -1, '_id': -1}"),
    @CompoundIndex(name = "admin_activity_actor_created_id_desc", def = "{'actorUsername': 1, 'createdOn': -1, '_id': -1}")
})
public class AdminActivity {
  @Id private String id;
  private String actorAccountId;
  private String actorUsername;
  private String action;
  private String targetType;
  private String targetId;
  private String targetLabel;
  private String reason;
  private String message;
  private Map<String, String> beforeValues;
  private Map<String, String> afterValues;
  private Map<String, String> metadata;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  private Instant createdOn;
}
