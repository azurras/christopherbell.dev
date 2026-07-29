package dev.christopherbell.post.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.account.model.Account;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class NewAccountVoidMutationLimiterTest {
  private static final Instant NOW = Instant.parse("2026-07-29T04:00:00Z");

  @Test
  void freshAccountsHaveIndependentPerActionBudgets() {
    var clock = new MutableClock(NOW);
    var limiter = new NewAccountVoidMutationLimiter(clock, properties(2, 1, 3, 1, 100));
    var account = Account.builder().id("new").createdOn(NOW.minus(Duration.ofDays(1))).build();

    limiter.require(account, VoidMutationKind.ROOT_POST);
    limiter.require(account, VoidMutationKind.ROOT_POST);
    assertThatThrownBy(() -> limiter.require(account, VoidMutationKind.ROOT_POST))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    assertThatCode(() -> limiter.require(account, VoidMutationKind.REPLY)).doesNotThrowAnyException();
  }

  @Test
  void oldAccountsBypassLimitsAndFreshWindowsResetAfterOneHour() {
    var clock = new MutableClock(NOW);
    var limiter = new NewAccountVoidMutationLimiter(clock, properties(1, 1, 1, 1, 100));
    var oldAccount = Account.builder().id("old").createdOn(NOW.minus(Duration.ofDays(8))).build();
    for (int i = 0; i < 20; i++) {
      limiter.require(oldAccount, VoidMutationKind.FOLLOW);
    }

    var fresh = Account.builder().id("fresh").createdOn(NOW.minus(Duration.ofDays(1))).build();
    limiter.require(fresh, VoidMutationKind.FOLLOW);
    assertThatThrownBy(() -> limiter.require(fresh, VoidMutationKind.FOLLOW))
        .isInstanceOf(ResponseStatusException.class);
    clock.advance(Duration.ofHours(1));
    assertThatCode(() -> limiter.require(fresh, VoidMutationKind.FOLLOW)).doesNotThrowAnyException();
  }

  @Test
  void trackedStateEvictsLeastRecentlyUsedKeysAtTheConfiguredBound() {
    var limiter = new NewAccountVoidMutationLimiter(
        new MutableClock(NOW), properties(1, 1, 1, 1, 2));

    limiter.require(fresh("a"), VoidMutationKind.ROOT_POST);
    limiter.require(fresh("b"), VoidMutationKind.ROOT_POST);
    limiter.require(fresh("c"), VoidMutationKind.ROOT_POST);

    assertThat(limiter.trackedWindowCount()).isEqualTo(2);
  }

  private static Account fresh(String id) {
    return Account.builder().id(id).createdOn(NOW.minus(Duration.ofDays(1))).build();
  }

  private static NewAccountVoidMutationLimitProperties properties(
      int root, int reply, int keepAlive, int follow, int maxKeys) {
    return new NewAccountVoidMutationLimitProperties(
        Duration.ofDays(7), Duration.ofHours(1), maxKeys, root, reply, keepAlive, follow);
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
