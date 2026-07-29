package dev.christopherbell.post.discovery;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.follow.AccountFollowStore;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.trust.AccountTrustRepository;
import dev.christopherbell.account.trust.model.AccountTrustType;
import dev.christopherbell.permission.PermissionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Privacy-aware people suggestions derived from shared active topics, never popularity. */
@Service
public final class VoidPeopleDiscoveryService {
  static final int MAX_SUGGESTIONS = 8;
  static final int CANDIDATE_POOL_SIZE = 128;

  private final VoidPeopleDiscoveryQueryRepository queries;
  private final AccountRepository accounts;
  private final AccountTrustRepository trust;
  private final AccountFollowStore follows;
  private final PermissionService permissions;
  private final Clock clock;

  public VoidPeopleDiscoveryService(
      VoidPeopleDiscoveryQueryRepository queries,
      AccountRepository accounts,
      AccountTrustRepository trust,
      AccountFollowStore follows,
      PermissionService permissions,
      Clock clock
  ) {
    this.queries = queries;
    this.accounts = accounts;
    this.trust = trust;
    this.follows = follows;
    this.permissions = permissions;
    this.clock = clock;
  }

  public List<VoidPersonSuggestion> suggestions() {
    Optional<String> selfId = permissions.hasAuthority("USER")
        ? Optional.of(permissions.getSelfId())
        : Optional.empty();
    return suggestions(selfId, clock.instant());
  }

  public List<VoidPersonSuggestion> suggestions(Optional<String> selfId, Instant now) {
    Objects.requireNonNull(selfId, "selfId");
    Objects.requireNonNull(now, "now");
    var candidates = queries.recentActiveCandidates(now, CANDIDATE_POOL_SIZE);
    var candidateAccounts = activeAccounts(candidates);
    return selfId.isPresent()
        ? signedInSuggestions(selfId.get(), now, candidates, candidateAccounts)
        : anonymousSuggestions(now, candidates, candidateAccounts);
  }

  private List<VoidPersonSuggestion> signedInSuggestions(
      String selfId,
      Instant now,
      List<VoidPersonCandidate> candidates,
      Map<String, Account> candidateAccounts
  ) {
    var self = accounts.findById(selfId).orElse(null);
    if (self == null || self.getStatus() != AccountStatus.ACTIVE) {
      return List.of();
    }
    var excluded = excludedAccountIds(self, candidates);
    var interests = queries.interestsFor(selfId, now);
    return candidates.stream()
        .filter(candidate -> !excluded.contains(candidate.accountId()))
        .filter(candidate -> candidateAccounts.containsKey(candidate.accountId()))
        .map(candidate -> scored(candidate, candidateAccounts.get(candidate.accountId()), interests))
        .sorted(Comparator
            .comparingInt(ScoredSuggestion::sharedTopicCount).reversed()
            .thenComparing(
                scored -> scored.suggestion().recentActivityOn(),
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(scored -> scored.suggestion().accountId()))
        .limit(MAX_SUGGESTIONS)
        .map(ScoredSuggestion::suggestion)
        .toList();
  }

  private List<VoidPersonSuggestion> anonymousSuggestions(
      Instant now,
      List<VoidPersonCandidate> candidates,
      Map<String, Account> candidateAccounts
  ) {
    var eligible = candidates.stream()
        .filter(candidate -> candidateAccounts.containsKey(candidate.accountId()))
        .toList();
    if (eligible.isEmpty()) {
      return List.of();
    }
    long utcDay = now.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    int offset = Math.floorMod(utcDay, eligible.size());
    var rotated = new ArrayList<VoidPersonSuggestion>(Math.min(MAX_SUGGESTIONS, eligible.size()));
    for (int index = 0; index < eligible.size() && rotated.size() < MAX_SUGGESTIONS; index++) {
      var candidate = eligible.get((offset + index) % eligible.size());
      var account = candidateAccounts.get(candidate.accountId());
      rotated.add(new VoidPersonSuggestion(
          account.getId(), account.getUsername(), List.of(), candidate.recentActivityOn(), false));
    }
    return List.copyOf(rotated);
  }

  private Map<String, Account> activeAccounts(List<VoidPersonCandidate> candidates) {
    var ids = candidates.stream().map(VoidPersonCandidate::accountId).distinct().toList();
    var result = new LinkedHashMap<String, Account>();
    accounts.findAllById(ids).stream()
        .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
        .forEach(account -> result.put(account.getId(), account));
    return result;
  }

  private Set<String> excludedAccountIds(
      Account self, List<VoidPersonCandidate> candidates) {
    var excluded = new HashSet<String>();
    var candidateIds = candidates.stream().map(VoidPersonCandidate::accountId).distinct().toList();
    excluded.add(self.getId());
    excluded.addAll(follows.followedAccountIds(
        self.getId(), org.springframework.data.domain.PageRequest.of(0, CANDIDATE_POOL_SIZE)));
    trust.findByOwnerAccountIdAndTargetAccountIdInAndTypeIn(
            self.getId(), candidateIds, List.of(AccountTrustType.MUTE, AccountTrustType.BLOCK))
        .forEach(relationship -> excluded.add(relationship.getTargetAccountId()));
    trust.findByTargetAccountIdAndOwnerAccountIdInAndType(
            self.getId(), candidateIds, AccountTrustType.BLOCK)
        .forEach(relationship -> excluded.add(relationship.getOwnerAccountId()));
    return Set.copyOf(excluded);
  }

  private static ScoredSuggestion scored(
      VoidPersonCandidate candidate, Account account, Set<String> interests) {
    var shared = new LinkedHashMap<String, String>();
    candidate.topics().stream()
        .filter(Objects::nonNull)
        .filter(topic -> interests.contains(topic.canonical()))
        .forEach(topic -> shared.putIfAbsent(topic.canonical(), topic.display()));
    return new ScoredSuggestion(
        new VoidPersonSuggestion(
            account.getId(),
            account.getUsername(),
            List.copyOf(shared.values()),
            candidate.recentActivityOn(),
            false),
        shared.size());
  }

  private record ScoredSuggestion(VoidPersonSuggestion suggestion, int sharedTopicCount) {}
}
