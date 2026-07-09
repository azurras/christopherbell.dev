package dev.christopherbell.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.christopherbell.message.model.Message;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.post.model.Post;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

class MongoIndexAnnotationTest {

  @Test
  @DisplayName("Post document declares indexes for feed and thread query paths")
  void postIndexes_matchRepositoryQueryPaths() {
    var indexes = compoundIndexes(Post.class);

    assertEquals("{'accountId': 1, 'createdOn': -1}", indexes.get("post_account_created_desc"));
    assertEquals("{'createdOn': -1}", indexes.get("post_created_desc"));
    assertEquals("{'rootId': 1, 'createdOn': 1}", indexes.get("post_root_created_asc"));
    assertEquals("{'parentId': 1}", indexes.get("post_parent"));
    assertEquals("{'expiresOn': 1}", indexes.get("post_expires"));
    assertEquals("{'accountId': 1, 'parentId': 1}", indexes.get("post_account_parent"));
  }

  @Test
  @DisplayName("Notification document declares indexes for inbox and unread count query paths")
  void notificationIndexes_matchRepositoryQueryPaths() {
    var indexes = compoundIndexes(Notification.class);

    assertEquals("{'accountId': 1, 'createdOn': -1}", indexes.get("notification_account_created_desc"));
    assertEquals("{'accountId': 1, 'read': 1}", indexes.get("notification_account_read"));
  }

  @Test
  @DisplayName("Message document declares indexes for conversation and unread query paths")
  void messageIndexes_matchRepositoryQueryPaths() {
    var indexes = compoundIndexes(Message.class);

    assertEquals("{'conversationKey': 1, 'createdOn': 1}", indexes.get("message_conversation_created_asc"));
    assertEquals("{'participantIds': 1, 'createdOn': -1}", indexes.get("message_participant_created_desc"));
    assertEquals(
        "{'recipientAccountId': 1, 'senderAccountId': 1, 'read': 1}",
        indexes.get("message_recipient_sender_read"));
  }

  private Map<String, String> compoundIndexes(Class<?> documentType) {
    var indexes = documentType.getAnnotation(CompoundIndexes.class);
    assertTrue(indexes != null, () -> documentType.getSimpleName() + " should declare compound indexes");
    return Arrays.stream(indexes.value())
        .collect(Collectors.toMap(CompoundIndex::name, CompoundIndex::def));
  }
}
