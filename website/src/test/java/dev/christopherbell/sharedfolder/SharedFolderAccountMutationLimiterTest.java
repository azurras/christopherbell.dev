package dev.christopherbell.sharedfolder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.sharedfolder.service.SharedFolderAccountMutationLimiter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SharedFolderAccountMutationLimiterTest {

  @Test
  void rejectsTheSixtyFirstMutationWithAStableResponse() {
    var limiter = new SharedFolderAccountMutationLimiter(
        Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC));
    Account account = account("account-1");

    for (int attempt = 0; attempt < 60; attempt++) {
      assertDoesNotThrow(() -> limiter.requireMutation(account));
    }
    ResponseStatusException failure = assertThrows(
        ResponseStatusException.class, () -> limiter.requireMutation(account));

    assertEquals(429, failure.getStatusCode().value());
    assertEquals("Too many shared-folder changes. Try again later.", failure.getReason());
  }

  @Test
  void boundedAccountStateEvictsLeastRecentlyUsedIdentity() {
    var limiter = new SharedFolderAccountMutationLimiter(
        Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC), 2, 1);

    limiter.requireMutation(account("old"));
    limiter.requireMutation(account("new"));
    limiter.requireMutation(account("newest"));

    assertDoesNotThrow(() -> limiter.requireMutation(account("old")));
  }

  private Account account(String id) {
    Account account = new Account();
    account.setId(id);
    return account;
  }
}
