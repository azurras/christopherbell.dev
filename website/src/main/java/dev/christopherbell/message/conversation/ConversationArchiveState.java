package dev.christopherbell.message.conversation;

import java.time.Instant;
import java.util.Set;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;

/** Owner-scoped archive marker; messages created after {@code archivedAt} restore visibility. */
@Data
@NoArgsConstructor
@CompoundIndex(
    name = "conversation_archive_owner_key_unique",
    def = "{'ownerAccountId': 1, 'conversationKey': 1}",
    unique = true)
public class ConversationArchiveState {
  @Id private String id;
  private String ownerAccountId;
  private String conversationKey;
  private Set<String> participantIds;
  private String archivedThroughMessageId;
  private Instant archivedAt;
}
