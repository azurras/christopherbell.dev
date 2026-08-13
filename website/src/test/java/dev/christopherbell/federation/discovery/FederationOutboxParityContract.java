package dev.christopherbell.federation.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared stable-cursor and TTL outbox behavior for real MongoDB and PostgreSQL. */
interface FederationOutboxParityContract {
  String RUN = java.util.UUID.randomUUID().toString();
  String ACCOUNT_ID = "outbox-parity-account-" + RUN;
  Instant NOW = Instant.parse("2026-08-13T18:00:00Z");

  PostRepository posts();

  FederationOutboxQueryPort outbox();

  StableCursorCodec cursors();

  void ensureAccount(Account account);

  @BeforeEach
  default void seedOutbox() {
    ensureAccount(Account.builder()
        .id(ACCOUNT_ID)
        .createdOn(NOW.minusSeconds(60))
        .email(ACCOUNT_ID + "@example.test")
        .passwordHash("hash")
        .role(dev.christopherbell.account.model.Role.USER)
        .status(dev.christopherbell.account.model.AccountStatus.ACTIVE)
        .username(ACCOUNT_ID)
        .build());
    posts().save(post("newer", NOW.minusSeconds(1), NOW.plusSeconds(60), true));
    posts().save(post("older", NOW.minusSeconds(2), NOW.plusSeconds(60), true));
    posts().save(post("expired", NOW.minusSeconds(3), NOW.minusSeconds(1), true));
    posts().save(post("private", NOW.minusSeconds(4), NOW.plusSeconds(60), false));
  }

  @Test
  default void pagesActiveEligiblePostsWithAStableCursorAndExactCount() throws Exception {
    var first = outbox().page(ACCOUNT_ID, Optional.empty(), 1, NOW);
    assertThat(first.items()).extracting(FederationOutboxEntry::id).containsExactly(id("newer"));
    assertThat(outbox().count(ACCOUNT_ID, NOW)).isEqualTo(2);

    var second = outbox().page(ACCOUNT_ID, cursors().decode(first.nextCursor()), 1, NOW);
    assertThat(second.items()).extracting(FederationOutboxEntry::id).containsExactly(id("older"));
    assertThat(second.nextCursor()).isNull();
  }

  private static Post post(
      String purpose, Instant createdOn, Instant expiresOn, boolean eligible) {
    return Post.builder()
        .id(id(purpose))
        .accountId(ACCOUNT_ID)
        .text(purpose)
        .rootId(id(purpose))
        .level(0)
        .createdOn(createdOn)
        .expiresOn(expiresOn)
        .federationOutboundEligible(eligible)
        .likesCount(0)
        .threadReplyLikesCount(0)
        .threadReplyCount(0)
        .build();
  }

  private static String id(String purpose) {
    return "outbox-parity-" + purpose + "-" + RUN;
  }
}
