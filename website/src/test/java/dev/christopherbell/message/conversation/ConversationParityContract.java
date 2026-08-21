package dev.christopherbell.message.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.message.MessageRepository;
import dev.christopherbell.message.model.Message;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared conversation query/archive behavior for real MongoDB and PostgreSQL. */
interface ConversationParityContract {
  String RUN = java.util.UUID.randomUUID().toString();
  String OWNER = "conversation-owner-" + RUN;
  String PEER = "conversation-peer-" + RUN;
  String OTHER = "conversation-other-" + RUN;
  String PAGE_KEY = "conversation-page:" + RUN;
  String ARCHIVE_KEY = "conversation-archive:" + RUN;
  Instant NOW = Instant.parse("2026-08-13T20:00:00Z");

  MessageRepository messages();

  ConversationQueryPort queries();

  ConversationArchivePort archives();

  StableCursorCodec cursors();

  void ensureAccount(Account account);

  @BeforeEach
  default void seedConversation() {
    ensureAccount(account(OWNER));
    ensureAccount(account(PEER));
    ensureAccount(account(OTHER));
    messages().save(message(id("page-a"), PEER, OWNER, PAGE_KEY, NOW, false));
    messages().save(message(id("page-b"), OWNER, PEER, PAGE_KEY, NOW.plusSeconds(1), true));
    messages().save(message(id("archive-a"), OTHER, OWNER, ARCHIVE_KEY, NOW, true));
    messages().save(message(
        id("archive-b"), OWNER, OTHER, ARCHIVE_KEY, NOW.plusSeconds(1), true));
  }

  @Test
  default void pagesAndUnreadCountsUseStableCursorAndRecipientScope() throws Exception {
    var first = queries().page(PAGE_KEY, Optional.empty(), 1);
    assertThat(first.items()).extracting(Message::getId).containsExactly(id("page-b"));
    var second = queries().page(PAGE_KEY, cursors().decode(first.nextCursor()), 1);
    assertThat(second.items()).extracting(Message::getId).containsExactly(id("page-a"));
    assertThat(queries().unreadCounts(OWNER, Set.of(PEER)))
        .containsEntry(PEER, 1L).doesNotContainKey(OTHER);
  }

  @Test
  default void archiveHidesLatestUntilANewerMessageArrives() {
    assertThat(queries().latestDistinctVisible(OWNER, 10))
        .extracting(Message::getId).contains(id("archive-b"));
    assertThat(archives().archive(OWNER, ARCHIVE_KEY, Set.of(OWNER, OTHER)).conversationKey())
        .isEqualTo(ARCHIVE_KEY);
    assertThat(queries().latestDistinctVisible(OWNER, 10))
        .extracting(Message::getConversationKey).doesNotContain(ARCHIVE_KEY);

    messages().save(message(
        id("archive-c"), OTHER, OWNER, ARCHIVE_KEY, NOW.plusSeconds(2), false));

    assertThat(queries().latestDistinctVisible(OWNER, 10))
        .extracting(Message::getId).contains(id("archive-c"));
  }

  private static Account account(String id) {
    return Account.builder().id(id).createdOn(NOW).email(id + "@example.test")
        .passwordHash("hash").role(dev.christopherbell.account.model.Role.USER)
        .status(dev.christopherbell.account.model.AccountStatus.ACTIVE).username(id).build();
  }

  private static Message message(
      String id, String sender, String recipient, String key, Instant createdOn, boolean read) {
    return Message.builder().id(id).conversationKey(key).participantIds(Set.of(sender, recipient))
        .senderAccountId(sender).recipientAccountId(recipient).text(id).read(read)
        .createdOn(createdOn).build();
  }

  private static String id(String purpose) {
    return "conversation-" + purpose + "-" + RUN;
  }
}
