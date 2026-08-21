package dev.christopherbell.post.hide;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared hidden-thread behavior executed against real MongoDB and PostgreSQL. */
interface HiddenPostThreadParityContract {
  String ACCOUNT = "hidden-owner";
  String ROOT = "hidden-root";
  Instant NOW = Instant.parse("2026-08-13T18:00:00Z");

  HiddenPostThreadRepository hiddenThreads();

  void ensureAccountAndPost(Account account, Post post);

  @BeforeEach
  default void resetHiddenFixture() {
    ensureAccountAndPost(account(), post());
    hiddenThreads().deleteByAccountIdAndRootPostId(ACCOUNT, ROOT);
  }

  @Test
  default void hiddenThreadRoundTripIsScopedAndDeleteIsIdempotent() {
    var hidden = HiddenPostThread.builder().id("hidden-contract").accountId(ACCOUNT)
        .rootPostId(ROOT).createdOn(NOW).build();

    assertThat(hiddenThreads().save(hidden)).extracting(HiddenPostThread::getRootPostId)
        .isEqualTo(ROOT);
    assertThat(hiddenThreads().findByAccountId(ACCOUNT))
        .extracting(HiddenPostThread::getRootPostId).containsExactly(ROOT);
    hiddenThreads().deleteByAccountIdAndRootPostId(ACCOUNT, ROOT);
    hiddenThreads().deleteByAccountIdAndRootPostId(ACCOUNT, ROOT);
    assertThat(hiddenThreads().findByAccountIdAndRootPostId(ACCOUNT, ROOT)).isEmpty();
  }

  private static Account account() {
    return Account.builder().id(ACCOUNT).createdOn(NOW).email(ACCOUNT + "@example.test")
        .passwordHash("hash").role(dev.christopherbell.account.model.Role.USER)
        .status(dev.christopherbell.account.model.AccountStatus.ACTIVE).username(ACCOUNT).build();
  }

  private static Post post() {
    return Post.builder().id(ROOT).accountId(ACCOUNT).text(ROOT).rootId(ROOT).level(0)
        .createdOn(NOW).expiresOn(NOW.plusSeconds(3600)).likesCount(0)
        .threadReplyLikesCount(0).threadReplyCount(0).build();
  }
}
