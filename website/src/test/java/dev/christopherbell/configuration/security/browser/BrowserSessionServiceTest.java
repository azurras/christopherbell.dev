package dev.christopherbell.configuration.security.browser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.permission.PermissionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BrowserSessionServiceTest {
  private static final Instant START = Instant.parse("2026-07-28T12:00:00Z");

  @Test
  void interactiveUseRenewsIdleExpiryButNeverMovesAbsoluteExpiry() {
    var fixture = new Fixture(START);
    String token = fixture.create();
    Instant absoluteExpiry = fixture.session().getAbsoluteExpiresOn();

    var result = fixture.at(START.plus(Duration.ofDays(6))).authenticate(token, true);

    assertTrue(result.isPresent());
    assertTrue(fixture.session().getIdleExpiresOn().isAfter(START.plus(Duration.ofDays(7))));
    assertTrue(fixture.session().getIdleExpiresOn().compareTo(absoluteExpiry) <= 0);
    assertTrue(fixture.session().getAbsoluteExpiresOn().equals(absoluteExpiry));
  }

  @Test
  void interactiveUseInsideActivityWindowDoesNotWrite() {
    var fixture = new Fixture(START);
    String token = fixture.create();
    org.mockito.Mockito.clearInvocations(fixture.sessions, fixture.activity);

    var authenticated = fixture.at(START.plus(Duration.ofMinutes(4)).plusSeconds(59))
        .authenticate(token, true);

    assertTrue(authenticated.isPresent());
    verifyNoInteractions(fixture.activity);
    verify(fixture.sessions, never()).save(any(BrowserSession.class));
  }

  @Test
  void dueInteractiveUseTouchesObservedSessionWithoutSavingTheDocument() {
    var fixture = new Fixture(START);
    String token = fixture.create();
    org.mockito.Mockito.clearInvocations(fixture.sessions, fixture.activity);
    Instant touchTime = START.plus(Duration.ofMinutes(5));

    var authenticated = fixture.at(touchTime).authenticate(token, true);

    assertTrue(authenticated.isPresent());
    verify(fixture.activity).touch(
        fixture.session().getId(), START, touchTime, touchTime.plus(BrowserSessionService.IDLE_LIFETIME));
    verify(fixture.sessions, never()).save(any(BrowserSession.class));
  }

  @Test
  void backgroundUseDoesNotRenewAndSessionExpiresAtSevenIdleDays() {
    var fixture = new Fixture(START);
    String token = fixture.create();

    assertTrue(fixture.at(START.plus(Duration.ofDays(6))).authenticate(token, false).isPresent());
    assertFalse(fixture.at(START.plus(Duration.ofDays(7))).authenticate(token, false).isPresent());
  }

  @Test
  void absoluteExpiryEndsAContinuouslyRenewedSessionAtThirtyDays() {
    var fixture = new Fixture(START);
    String token = fixture.create();

    for (int day : new int[] {6, 12, 18, 24, 29}) {
      var renewal = fixture.at(START.plus(Duration.ofDays(day)))
          .authenticate(token, true).orElseThrow();
      token = renewal.rotatedToken().orElse(token);
    }
    assertFalse(fixture.at(START.plus(Duration.ofDays(30))).authenticate(token, true).isPresent());
  }

  @Test
  void rotationKeepsThePreviousCredentialValidForBriefRequestOverlap() {
    var fixture = new Fixture(START);
    String original = fixture.create();

    var rotated = fixture.at(START.plus(Duration.ofDays(2))).authenticate(original, true).orElseThrow();

    assertTrue(rotated.rotatedToken().isPresent());
    assertNotEquals(original, rotated.rotatedToken().orElseThrow());
    assertTrue(fixture.at(START.plus(Duration.ofDays(2)).plus(BrowserSessionService.ROTATION_OVERLAP)
            .minusNanos(1))
        .authenticate(original, false).isPresent());
    assertFalse(fixture.at(START.plus(Duration.ofDays(2)).plus(BrowserSessionService.ROTATION_OVERLAP))
        .authenticate(original, false).isPresent());
  }

  @Test
  void rotationWithLessThanFullOverlapRemainingTouchesWithoutIssuingReplacement() {
    var fixture = new Fixture(START);
    String token = fixture.create();
    Instant now = START.plus(BrowserSessionService.ABSOLUTE_LIFETIME)
        .minus(BrowserSessionService.ROTATION_OVERLAP)
        .plusNanos(1);
    fixture.session().setIdleExpiresOn(fixture.session().getAbsoluteExpiresOn());
    fixture.session().setLastSeenOn(now.minus(BrowserSessionService.ACTIVITY_WRITE_INTERVAL));
    fixture.session().setRotatedOn(now.minus(BrowserSessionService.ROTATION_INTERVAL));
    org.mockito.Mockito.clearInvocations(fixture.sessions, fixture.activity);

    var authenticated = fixture.at(now).authenticate(token, true).orElseThrow();

    assertTrue(authenticated.rotatedToken().isEmpty());
    verify(fixture.activity).touch(
        fixture.session().getId(),
        now.minus(BrowserSessionService.ACTIVITY_WRITE_INTERVAL),
        now,
        fixture.session().getAbsoluteExpiresOn());
    verify(fixture.activity, never()).rotate(anyString(), anyString(), any(), anyString(), any(), any(), any());
  }

  @Test
  void lostRotationCompareAndSetRejectsAuthenticationWithoutSaving() {
    var fixture = new Fixture(START);
    String token = fixture.create();
    fixture.rejectRotation = true;
    org.mockito.Mockito.clearInvocations(fixture.sessions);

    var authenticated = fixture.at(START.plus(BrowserSessionService.ROTATION_INTERVAL))
        .authenticate(token, true);

    assertFalse(authenticated.isPresent());
    verify(fixture.activity).rotate(
        eq(fixture.session().getId()), anyString(), eq(START), anyString(),
        eq(START.plus(BrowserSessionService.ROTATION_INTERVAL)), any(), any());
    verify(fixture.sessions, never()).save(any(BrowserSession.class));
  }

  @Test
  void revocationThatWinsBeforeRotationRejectsAuthentication() {
    var fixture = new Fixture(START);
    String token = fixture.create();
    fixture.revokeDuringRotation = true;
    org.mockito.Mockito.clearInvocations(fixture.sessions);

    var authenticated = fixture.at(START.plus(BrowserSessionService.ROTATION_INTERVAL))
        .authenticate(token, true);

    assertFalse(authenticated.isPresent());
    verify(fixture.sessions, never()).save(any(BrowserSession.class));
  }

  @Test
  void authenticationUsesTheStoredRoleWithoutLoadingTheAccount() {
    var fixture = new Fixture(START);
    String token = fixture.create();
    org.mockito.Mockito.clearInvocations(fixture.accounts);

    var authenticated = fixture.authenticate(token, false).orElseThrow();

    org.assertj.core.api.Assertions.assertThat(authenticated.role()).isEqualTo(Role.USER);
    org.mockito.Mockito.verifyNoInteractions(fixture.accounts);
  }

  @Test
  void rolelessAccountCannotCreateAnIncompleteBrowserSession() {
    var fixture = new Fixture(START);
    fixture.account.setRole(null);
    String loginJwt = PermissionService.generateToken(fixture.account);

    assertThrows(IllegalArgumentException.class, () -> fixture.service().create(loginJwt));

    assertTrue(fixture.saved.getAllValues().isEmpty());
  }

  @Test
  void legacySessionWithoutRoleIsDeletedAndRejected() {
    var fixture = new Fixture(START);
    String token = fixture.create();
    fixture.session().setRole(null);

    assertFalse(fixture.authenticate(token, false).isPresent());

    org.mockito.Mockito.verify(fixture.sessions).delete(fixture.session());
  }

  @Test
  void passwordResetBetweenJwtMintAndSessionExchangeIsRejected() {
    var fixture = new Fixture(START);
    String staleLoginJwt = PermissionService.generateToken(fixture.account);
    fixture.account.setPasswordHash("reset-password-hash");

    assertThrows(IllegalArgumentException.class, () -> fixture.service().create(staleLoginJwt));

    assertTrue(fixture.saved.getAllValues().isEmpty());
  }

  private static final class Fixture {
    private final BrowserSessionRepository sessions = mock(BrowserSessionRepository.class);
    private final BrowserSessionActivityStore activity = mock(BrowserSessionActivityStore.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final ArgumentCaptor<BrowserSession> saved = ArgumentCaptor.forClass(BrowserSession.class);
    private final Account account = Account.builder()
        .id("account-1")
        .passwordHash("password-hash")
        .role(Role.USER)
        .permissions(Set.of())
        .status(AccountStatus.ACTIVE)
        .build();
    private Instant now;
    private boolean revoked;
    private boolean rejectRotation;
    private boolean revokeDuringRotation;

    private Fixture(Instant now) {
      this.now = now;
      when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
      when(sessions.save(saved.capture())).thenAnswer(invocation -> invocation.getArgument(0));
      when(sessions.findById(anyString())).thenAnswer(invocation -> {
        if (revoked || saved.getAllValues().isEmpty()) return Optional.empty();
        var current = session();
        return current.getId().equals(invocation.getArgument(0))
            ? Optional.of(current)
            : Optional.empty();
      });
      when(activity.touch(anyString(), any(), any(), any())).thenAnswer(invocation -> {
        var current = session();
        current.setLastSeenOn(invocation.getArgument(2));
        current.setIdleExpiresOn(invocation.getArgument(3));
        return Optional.of(current);
      });
      when(activity.rotate(anyString(), anyString(), any(), anyString(), any(), any(), any()))
          .thenAnswer(invocation -> {
            if (revokeDuringRotation) {
              revoked = true;
              return Optional.empty();
            }
            if (rejectRotation) return Optional.empty();
            var current = session();
            current.setPreviousTokenHash(invocation.getArgument(1));
            current.setPreviousTokenExpiresOn(invocation.getArgument(5));
            current.setTokenHash(invocation.getArgument(3));
            current.setRotatedOn(invocation.getArgument(4));
            current.setLastSeenOn(invocation.getArgument(4));
            current.setIdleExpiresOn(invocation.getArgument(6));
            return Optional.of(current);
          });
    }

    private String create() {
      return service().create(PermissionService.generateToken(account));
    }

    private Optional<AuthenticatedBrowserSession> authenticate(String token, boolean interactive) {
      return service().authenticate(token, interactive);
    }

    private Fixture at(Instant next) {
      now = next;
      return this;
    }

    private BrowserSession session() {
      return saved.getAllValues().getLast();
    }

    private BrowserSessionService service() {
      return new BrowserSessionService(
          sessions, activity, accounts, Clock.fixed(now, ZoneOffset.UTC));
    }
  }
}
