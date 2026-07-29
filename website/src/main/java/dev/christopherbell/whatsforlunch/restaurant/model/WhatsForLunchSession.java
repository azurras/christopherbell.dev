package dev.christopherbell.whatsforlunch.restaurant.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Mongo-backed group lunch session with fixed restaurants and participant votes.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
@CompoundIndexes({
    @CompoundIndex(
        name = "wfl_session_participant_created",
        def = "{'participantAccountIds': 1, 'createdOn': -1, '_id': 1}")
})
@Document("whatsforlunch_sessions")
public class WhatsForLunchSession {
  private final String type = "whatsforlunch_session";

  @Id
  private String id;

  @Indexed
  private String createdByAccountId;
  private String createdByUsername;

  @Indexed
  private List<String> participantAccountIds;
  private Map<String, String> participantUsernamesByAccountId;
  private List<String> restaurantIds;
  private Map<String, String> votesByAccountId;
  private long revision;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant activeUntil;

  @Indexed(name = "wfl_session_delete_ttl", expireAfter = "0s")
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant deleteOn;

  private long restaurantResetCount;
  private List<WhatsForLunchRestaurantResetAudit> restaurantResetAudit;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant createdOn;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant lastUpdatedOn;
}
