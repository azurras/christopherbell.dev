package dev.christopherbell.post.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.follow.AccountFollowStore;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.trust.AccountTrustRelationship;
import dev.christopherbell.account.trust.AccountTrustRepository;
import dev.christopherbell.account.trust.model.AccountTrustType;
import dev.christopherbell.post.model.PostTopic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoidPeopleDiscoveryServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-29T04:00:00Z");

  @Mock private VoidPeopleDiscoveryQueryRepository queries;
  @Mock private AccountRepository accounts;
  @Mock private AccountTrustRepository trust;
  @Mock private AccountFollowStore follows;

  @Test
  void signedInSuggestionsRankDistinctOverlapAndApplyEveryPrivacyExclusion() {
    var service = new VoidPeopleDiscoveryService(queries, accounts, trust, follows, null, null);
    var self = account("self", "self", AccountStatus.ACTIVE);
    var candidates = List.of(
        candidate("best", NOW.minusSeconds(30), "music", "java"),
        candidate("recent", NOW.minusSeconds(1), "music"),
        candidate("followed", NOW.minusSeconds(2), "music", "java"),
        candidate("outgoing-block", NOW.minusSeconds(3), "music", "java"),
        candidate("incoming-block", NOW.minusSeconds(4), "music", "java"),
        candidate("suspended", NOW.minusSeconds(5), "music", "java"));
    when(accounts.findById("self")).thenReturn(Optional.of(self));
    when(follows.followedAccountIds(eq("self"), any())).thenReturn(List.of("followed"));
    when(queries.interestsFor("self", NOW)).thenReturn(Set.of("music", "java"));
    when(queries.recentActiveCandidates(NOW, 128)).thenReturn(candidates);
    when(accounts.findAllById(any())).thenReturn(List.of(
        account("best", "best-user", AccountStatus.ACTIVE),
        account("recent", "recent-user", AccountStatus.ACTIVE),
        account("followed", "followed-user", AccountStatus.ACTIVE),
        account("outgoing-block", "outgoing", AccountStatus.ACTIVE),
        account("incoming-block", "incoming", AccountStatus.ACTIVE),
        account("suspended", "suspended-user", AccountStatus.SUSPENDED)));
    when(trust.findByOwnerAccountIdAndTargetAccountIdInAndTypeIn(eq("self"), any(), any()))
        .thenReturn(List.of(relationship("self", "outgoing-block", AccountTrustType.BLOCK)));
    when(trust.findByTargetAccountIdAndOwnerAccountIdInAndType(
        eq("self"), any(), eq(AccountTrustType.BLOCK)))
        .thenReturn(List.of(relationship("incoming-block", "self", AccountTrustType.BLOCK)));

    var result = service.suggestions(Optional.of("self"), NOW);

    assertThat(result).extracting(VoidPersonSuggestion::accountId)
        .containsExactly("best", "recent");
    assertThat(result.get(0).sharedTopics()).containsExactly("Music", "Java");
    assertThat(result).allSatisfy(suggestion -> assertThat(suggestion.followed()).isFalse());
  }

  @Test
  void anonymousRotationIsStableWithinOneUtcDayAndChangesAcrossDays() {
    var service = new VoidPeopleDiscoveryService(queries, accounts, trust, follows, null, null);
    var candidates = java.util.stream.IntStream.range(0, 10)
        .mapToObj(index -> candidate("a" + index, NOW.minusSeconds(index), "music"))
        .toList();
    when(queries.recentActiveCandidates(any(), eq(128))).thenReturn(candidates);
    when(accounts.findAllById(any())).thenAnswer(invocation -> {
      Iterable<String> ids = invocation.getArgument(0);
      var result = new java.util.ArrayList<Account>();
      ids.forEach(id -> result.add(account(id, id + "-user", AccountStatus.ACTIVE)));
      return result;
    });

    var first = service.suggestions(Optional.empty(), NOW);
    var again = service.suggestions(Optional.empty(), NOW.plusSeconds(60));
    var tomorrow = service.suggestions(Optional.empty(), NOW.plusSeconds(86_400));

    assertThat(first).hasSize(8);
    assertThat(again).extracting(VoidPersonSuggestion::accountId)
        .containsExactlyElementsOf(first.stream().map(VoidPersonSuggestion::accountId).toList());
    assertThat(tomorrow).extracting(VoidPersonSuggestion::accountId)
        .isNotEqualTo(first.stream().map(VoidPersonSuggestion::accountId).toList());
  }

  private static VoidPersonCandidate candidate(String id, Instant activity, String... topics) {
    return new VoidPersonCandidate(
        id,
        java.util.Arrays.stream(topics)
            .map(topic -> new PostTopic(topic, Character.toUpperCase(topic.charAt(0)) + topic.substring(1)))
            .toList(),
        activity);
  }

  private static Account account(String id, String username, AccountStatus status) {
    return Account.builder().id(id).username(username).status(status).build();
  }

  private static AccountTrustRelationship relationship(
      String owner, String target, AccountTrustType type) {
    return AccountTrustRelationship.builder()
        .ownerAccountId(owner)
        .targetAccountId(target)
        .type(type)
        .build();
  }
}
