package dev.christopherbell.account.trust;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.account.follow.AccountFollowStore;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.trust.model.AccountTrustType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

/** Shared relationship behavior executed against real MongoDB and PostgreSQL adapters. */
interface IdentityRelationshipParityContract {
  String OWNER = "relationship-owner";
  String TARGET = "relationship-target";
  String OTHER = "relationship-other";
  Instant NOW = Instant.parse("2026-08-13T16:00:00Z");

  AccountFollowStore followStore();

  AccountTrustRepository trustRepository();

  void ensureAccount(Account account);

  @BeforeEach
  default void resetRelationships() {
    ensureAccount(account(OWNER));
    ensureAccount(account(TARGET));
    ensureAccount(account(OTHER));
    followStore().deleteForAccount(OWNER);
    followStore().deleteForAccount(TARGET);
    followStore().deleteForAccount(OTHER);
    for (var target : List.of(TARGET, OTHER)) {
      for (var type : AccountTrustType.values()) {
        trustRepository().deleteByOwnerAccountIdAndTargetAccountIdAndType(OWNER, target, type);
      }
    }
  }

  @Test
  default void followTransitionsAreIdempotentOrderedAndDeleteBothDirections() {
    assertThat(followStore().follow(OWNER, TARGET, NOW).created()).isTrue();
    assertThat(followStore().follow(OWNER, TARGET, NOW).created()).isFalse();
    assertThat(followStore().follow(OWNER, OTHER, NOW.plusSeconds(1)).created()).isTrue();
    assertThat(followStore().follow(OTHER, OWNER, NOW.plusSeconds(2)).created()).isTrue();

    assertThat(followStore().followedAccountIds(OWNER, PageRequest.of(0, 10)))
        .containsExactly(TARGET, OTHER);
    assertThat(followStore().followerAccountIds(OWNER, PageRequest.of(0, 10)))
        .containsExactly(OTHER);
    assertThat(followStore().countFollowing(OWNER)).isEqualTo(2);
    assertThat(followStore().countFollowers(OWNER)).isOne();

    followStore().deleteForAccount(OWNER);
    assertThat(followStore().exists(OWNER, TARGET)).isFalse();
    assertThat(followStore().exists(OTHER, OWNER)).isFalse();
  }

  @Test
  default void trustQueriesPreserveDirectionTypeAndEmptyCollections() {
    var muted = trustRepository().save(relationship("relationship-muted", TARGET,
        AccountTrustType.MUTE, NOW));
    trustRepository().save(relationship("relationship-blocked", OTHER,
        AccountTrustType.BLOCK, NOW.plusSeconds(1)));

    assertThat(trustRepository().findByOwnerAccountIdAndTargetAccountIdAndType(
        OWNER, TARGET, AccountTrustType.MUTE)).contains(muted);
    assertThat(trustRepository().findByOwnerAccountIdAndTypeIn(
        OWNER, List.of(AccountTrustType.MUTE))).extracting(AccountTrustRelationship::getId)
        .containsExactly("relationship-muted");
    assertThat(trustRepository().findByOwnerAccountIdAndTargetAccountIdInAndTypeIn(
        OWNER, List.of(TARGET, OTHER), List.of(AccountTrustType.BLOCK)))
        .extracting(AccountTrustRelationship::getId).containsExactly("relationship-blocked");
    assertThat(trustRepository().findByOwnerAccountIdAndTypeIn(OWNER, List.of())).isEmpty();

    trustRepository().deleteByOwnerAccountIdAndTargetAccountIdAndType(
        OWNER, TARGET, AccountTrustType.MUTE);
    assertThat(trustRepository().existsByOwnerAccountIdAndTargetAccountIdAndType(
        OWNER, TARGET, AccountTrustType.MUTE)).isFalse();
  }

  private static Account account(String id) {
    return Account.builder().id(id).createdOn(NOW).email(id + "@example.test")
        .passwordHash("hash").role(dev.christopherbell.account.model.Role.USER)
        .status(dev.christopherbell.account.model.AccountStatus.ACTIVE).username(id).build();
  }

  private static AccountTrustRelationship relationship(
      String id, String target, AccountTrustType type, Instant createdOn) {
    return AccountTrustRelationship.builder().id(id).ownerAccountId(OWNER)
        .targetAccountId(target).type(type).createdOn(createdOn).build();
  }
}
