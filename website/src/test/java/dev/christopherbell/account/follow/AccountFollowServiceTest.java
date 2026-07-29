package dev.christopherbell.account.follow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.dto.AccountProfile;
import dev.christopherbell.account.profile.AccountProfileService;
import dev.christopherbell.post.abuse.NewAccountVoidMutationLimiter;
import dev.christopherbell.post.abuse.VoidMutationKind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountFollowServiceTest {
  @Mock private AccountProfileService profiles;
  @Mock private AccountFollowStore follows;
  @Mock private NewAccountVoidMutationLimiter limiter;
  private AccountFollowService service;

  @BeforeEach
  void setUp() {
    service = new AccountFollowService(
        profiles,
        follows,
        limiter,
        Clock.fixed(Instant.parse("2026-07-29T04:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void duplicateFollowDoesNotConsumeAnAddBudget() throws Exception {
    var self = Account.builder().id("self").build();
    var target = Account.builder().id("target").username("target").build();
    var profile = AccountProfile.builder().username("target").followedByMe(true).build();
    when(profiles.getSelfEntity()).thenReturn(self);
    when(profiles.findBySanitizedUsername("target")).thenReturn(target);
    when(profiles.toPublicProfile(target, Optional.of(self))).thenReturn(profile);
    when(follows.follow("self", "target", Instant.parse("2026-07-29T04:00:00Z")))
        .thenReturn(new AccountFollowStore.FollowTransition(false, false));

    assertThat(service.followAccount("target").followedByMe()).isTrue();

    verify(limiter, never()).require(self, VoidMutationKind.FOLLOW);
    verify(follows).follow("self", "target", Instant.parse("2026-07-29T04:00:00Z"));
  }

  @Test
  void unfollowNeverConsumesAnAddBudget() throws Exception {
    var self = Account.builder().id("self").build();
    var target = Account.builder().id("target").username("target").build();
    var profile = AccountProfile.builder().username("target").followedByMe(false).build();
    when(profiles.getSelfEntity()).thenReturn(self);
    when(profiles.findBySanitizedUsername("target")).thenReturn(target);
    when(profiles.toPublicProfile(target, Optional.of(self))).thenReturn(profile);

    assertThat(service.unfollowAccount("target").followedByMe()).isFalse();

    verify(limiter, never()).require(self, VoidMutationKind.FOLLOW);
    verify(follows).unfollow("self", "target");
  }
}
