package dev.christopherbell.admin.commandcenter.action;

import static dev.christopherbell.admin.commandcenter.action.CommandCenterActionType.RESTART_COMPUTER;
import static dev.christopherbell.admin.commandcenter.action.CommandCenterActionType.RESTART_SITE;
import static dev.christopherbell.admin.commandcenter.action.CommandCenterActionType.SHUTDOWN_COMPUTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.admin.activity.AdminActivityService;
import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import dev.christopherbell.admin.commandcenter.action.CommandCenterActionService.ActionConfirmation;
import dev.christopherbell.configuration.ClientIpResolver;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.security.PasswordUtil;
import dev.christopherbell.permission.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

class CommandCenterActionServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-12T12:00:00Z");
  private static final String PASSWORD = "correct horse battery staple";

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final PermissionService permissions = mock(PermissionService.class);
  private final AdminActivityService activities = mock(AdminActivityService.class);
  private final ClientIpResolver clientIps = mock(ClientIpResolver.class);
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final TaskScheduler scheduler = mock(TaskScheduler.class);
  private final MutableClock clock = new MutableClock(NOW);
  private final List<CommandCenterActionType> executed = new ArrayList<>();
  private final CommandExecutor executor = executed::add;
  private final CommandCenterProperties properties = properties();
  private CommandCenterActionService service;
  private Account actor;

  @BeforeEach
  void setUp() {
    actor = account("admin-1", Role.ADMIN, AccountStatus.ACTIVE, true);
    when(permissions.getSelfId()).thenReturn(actor.getId());
    when(accounts.findById(actor.getId())).thenReturn(Optional.of(actor));
    when(clientIps.resolveClientIp(request)).thenReturn("203.0.113.9");
    service = new CommandCenterActionService(
        properties, accounts, permissions, activities, clientIps, executor, scheduler,
        clock, new SecureRandom());
  }

  @Test
  void challengeRequiresFreshActiveApprovedAdminAccount() {
    for (var rejected : List.of(
        account("user", Role.USER, AccountStatus.ACTIVE, true),
        account("inactive", Role.ADMIN, AccountStatus.INACTIVE, true),
        account("unapproved", Role.ADMIN, AccountStatus.ACTIVE, false))) {
      when(permissions.getSelfId()).thenReturn(rejected.getId());
      when(accounts.findById(rejected.getId())).thenReturn(Optional.of(rejected));
      assertThatThrownBy(() -> service.createChallenge(RESTART_SITE))
          .isInstanceOf(InvalidRequestException.class);
    }
  }

  @Test
  void executionRechecksThePersistedAccountStateAfterChallengeCreation() throws Exception {
    var challenge = service.createChallenge(RESTART_SITE);
    actor.setStatus(AccountStatus.INACTIVE);

    assertThatThrownBy(() -> execute(challenge.id(), RESTART_SITE, PASSWORD, "RESTART SITE"))
        .isInstanceOf(InvalidRequestException.class);

    verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    assertThat(executed).isEmpty();
  }

  @Test
  void challengeIsAccountActionAndExpiryBoundAndSingleUse() throws Exception {
    var challenge = service.createChallenge(RESTART_SITE);
    assertThat(challenge.action()).isEqualTo(RESTART_SITE);
    assertThat(challenge.confirmationPhrase()).isEqualTo("RESTART SITE");
    assertThat(challenge.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));

    assertThatThrownBy(() -> execute(challenge.id(), SHUTDOWN_COMPUTER, PASSWORD, "SHUTDOWN COMPUTER"))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> execute(challenge.id(), RESTART_SITE, PASSWORD, "RESTART SITE"))
        .isInstanceOf(InvalidRequestException.class);

    var owned = service.createChallenge(RESTART_SITE);
    when(permissions.getSelfId()).thenReturn("admin-2");
    when(accounts.findById("admin-2"))
        .thenReturn(Optional.of(account("admin-2", Role.ADMIN, AccountStatus.ACTIVE, true)));
    assertThatThrownBy(() -> execute(owned.id(), RESTART_SITE, PASSWORD, "RESTART SITE"))
        .isInstanceOf(InvalidRequestException.class);

    when(permissions.getSelfId()).thenReturn(actor.getId());
    var expired = service.createChallenge(RESTART_SITE);
    clock.advance(Duration.ofMinutes(2).plusMillis(1));
    assertThatThrownBy(() -> execute(expired.id(), RESTART_SITE, PASSWORD, "RESTART SITE"))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void concurrentDoubleSubmitConsumesChallengeAtomically() throws Exception {
    var challenge = service.createChallenge(RESTART_COMPUTER);
    var confirmation = new ActionConfirmation(
        challenge.id(), RESTART_COMPUTER, PASSWORD, "RESTART COMPUTER");
    var successes = new java.util.concurrent.atomic.AtomicInteger(0);
    var start = new java.util.concurrent.CountDownLatch(1);
    var done = new java.util.concurrent.CountDownLatch(2);
    for (int i = 0; i < 2; i++) {
      Thread.startVirtualThread(() -> {
        try {
          start.await();
          service.execute(confirmation, request);
          successes.incrementAndGet();
        } catch (Exception ignored) {
          // One loser is required: the challenge was already atomically consumed.
        } finally {
          done.countDown();
        }
      });
    }
    start.countDown();
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(successes).hasValue(1);
    assertThat(executed).containsExactly(RESTART_COMPUTER);
  }

  @Test
  void concurrentDistinctChallengesCannotBypassTheAcceptedActionCooldown() throws Exception {
    var barrierClock = new CooldownRaceClock(NOW);
    var concurrentExecutions = java.util.Collections.synchronizedList(
        new ArrayList<CommandCenterActionType>());
    var concurrentService = new CommandCenterActionService(
        properties, accounts, permissions, activities, clientIps, concurrentExecutions::add,
        scheduler, barrierClock, new SecureRandom());
    var first = concurrentService.createChallenge(RESTART_COMPUTER);
    var second = concurrentService.createChallenge(RESTART_COMPUTER);
    var confirmations = List.of(
        new ActionConfirmation(
            first.id(), RESTART_COMPUTER, PASSWORD, "RESTART COMPUTER"),
        new ActionConfirmation(
            second.id(), RESTART_COMPUTER, PASSWORD, "RESTART COMPUTER"));
    var successes = new java.util.concurrent.atomic.AtomicInteger();
    var done = new java.util.concurrent.CountDownLatch(2);

    for (var confirmation : confirmations) {
      Thread.startVirtualThread(() -> {
        try {
          concurrentService.execute(confirmation, request);
          successes.incrementAndGet();
        } catch (Exception ignored) {
          // The cooldown must reject one independently valid challenge.
        } finally {
          done.countDown();
        }
      });
    }

    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(successes).hasValue(1);
    assertThat(concurrentExecutions).containsExactly(RESTART_COMPUTER);
  }

  @Test
  void passwordAndExactPhraseFailuresConsumeChallengeAndThrottleAfterThree() throws Exception {
    var first = service.createChallenge(RESTART_SITE);
    assertThatThrownBy(() -> execute(first.id(), RESTART_SITE, "wrong", "RESTART SITE"))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> execute(first.id(), RESTART_SITE, PASSWORD, "RESTART SITE"))
        .isInstanceOf(InvalidRequestException.class);

    var second = service.createChallenge(RESTART_SITE);
    assertThatThrownBy(() -> execute(second.id(), RESTART_SITE, PASSWORD, "restart site"))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> service.createChallenge(RESTART_SITE))
        .isInstanceOf(InvalidRequestException.class);

    clock.advance(Duration.ofMinutes(15).plusMillis(1));
    var third = service.createChallenge(RESTART_SITE);
    assertThatThrownBy(() -> execute(third.id(), RESTART_SITE, PASSWORD, "RESTART SITE "))
        .isInstanceOf(InvalidRequestException.class);
    assertThat(service.createChallenge(RESTART_SITE)).isNotNull();
  }

  @Test
  void invalidChallengeFailuresAlsoContributeToTheAttemptWindow() throws Exception {
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatThrownBy(() -> execute(
          "missing-challenge", RESTART_SITE, PASSWORD, "RESTART SITE"))
          .isInstanceOf(InvalidRequestException.class);
    }

    assertThatThrownBy(() -> service.createChallenge(RESTART_SITE))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void confirmationStringRepresentationRedactsAllSubmittedSecrets() {
    var confirmation = new ActionConfirmation(
        "secret-challenge-id", RESTART_SITE, PASSWORD, "RESTART SITE");

    assertThat(confirmation.toString())
        .doesNotContain("secret-challenge-id", PASSWORD, "RESTART SITE")
        .contains("<redacted>");
  }

  @Test
  void acceptedActionEnforcesCooldownAndAuditsOnlySafeMetadata() throws Exception {
    var challenge = service.createChallenge(RESTART_COMPUTER);
    var result = execute(challenge.id(), RESTART_COMPUTER, PASSWORD, "RESTART COMPUTER");

    assertThat(result.accepted()).isTrue();
    assertThat(result.action()).isEqualTo(RESTART_COMPUTER);
    assertThat(service.pendingAction()).get()
        .extracting("action", "executeAt", "cancellable")
        .containsExactly("RESTART_COMPUTER", NOW.plusSeconds(60), true);
    assertThat(executed).containsExactly(RESTART_COMPUTER);

    @SuppressWarnings("unchecked")
    var metadata = ArgumentCaptor.forClass(
        (Class<java.util.Map<String, String>>) (Class<?>) java.util.Map.class);
    verify(activities).recordForActor(
        org.mockito.ArgumentMatchers.eq("admin-1"), org.mockito.ArgumentMatchers.eq("admin"),
        org.mockito.ArgumentMatchers.eq("COMMAND_CENTER_ACTION_ACCEPTED"),
        org.mockito.ArgumentMatchers.eq("command-center"),
        org.mockito.ArgumentMatchers.eq("RESTART_COMPUTER"),
        org.mockito.ArgumentMatchers.eq("RESTART_COMPUTER"),
        any(), metadata.capture());
    assertThat(metadata.getValue()).containsOnlyKeys("action", "clientIp", "mode", "outcome");
    assertThat(metadata.getValue().toString())
        .doesNotContain(PASSWORD, "Bearer", "password", "challenge");

    var immediate = service.createChallenge(RESTART_COMPUTER);
    assertThatThrownBy(() -> execute(
        immediate.id(), RESTART_COMPUTER, PASSWORD, "RESTART COMPUTER"))
        .isInstanceOf(InvalidRequestException.class);
    clock.advance(Duration.ofMinutes(2).plusMillis(1));
    var later = service.createChallenge(RESTART_COMPUTER);
    assertThat(execute(later.id(), RESTART_COMPUTER, PASSWORD, "RESTART COMPUTER").accepted()).isTrue();
  }

  @Test
  void websiteRestartIsScheduledSoAcceptanceCanReturnBeforeLaunch() throws Exception {
    var challenge = service.createChallenge(RESTART_SITE);
    var result = execute(challenge.id(), RESTART_SITE, PASSWORD, "RESTART SITE");

    assertThat(result.accepted()).isTrue();
    assertThat(executed).isEmpty();
    verify(activities).recordForActor(
        org.mockito.ArgumentMatchers.eq("admin-1"), org.mockito.ArgumentMatchers.eq("admin"),
        org.mockito.ArgumentMatchers.eq("COMMAND_CENTER_ACTION_ACCEPTED"), any(), any(), any(),
        any(), any());

    var scheduledAction = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).schedule(scheduledAction.capture(), any(Instant.class));
    reset(permissions);
    scheduledAction.getValue().run();

    assertThat(executed).containsExactly(RESTART_SITE);
    verify(permissions, never()).getSelfId();
    verify(activities).recordForActor(
        org.mockito.ArgumentMatchers.eq("admin-1"), org.mockito.ArgumentMatchers.eq("admin"),
        org.mockito.ArgumentMatchers.eq("COMMAND_CENTER_ACTION_LAUNCHED"), any(), any(), any(),
        any(), any());
  }

  @Test
  void cancellationUsesOnlyTheFixedCancelActionAndClearsPendingState() throws Exception {
    var challenge = service.createChallenge(SHUTDOWN_COMPUTER);
    execute(challenge.id(), SHUTDOWN_COMPUTER, PASSWORD, "SHUTDOWN COMPUTER");

    var result = service.cancel(request);

    assertThat(result.action()).isEqualTo(CommandCenterActionType.CANCEL_PENDING_ACTION);
    assertThat(service.pendingAction()).isEmpty();
    assertThat(executed).containsExactly(
        SHUTDOWN_COMPUTER, CommandCenterActionType.CANCEL_PENDING_ACTION);
  }

  @Test
  void failedCancellationLaunchRestoresPendingStateForRetry() throws Exception {
    var failCancellation = new java.util.concurrent.atomic.AtomicBoolean(true);
    CommandExecutor retryableExecutor = action -> {
      if (action == CommandCenterActionType.CANCEL_PENDING_ACTION
          && failCancellation.get()) {
        throw new java.io.IOException("simulated fixed cancel launch failure");
      }
      executed.add(action);
    };
    var retryableService = new CommandCenterActionService(
        properties, accounts, permissions, activities, clientIps, retryableExecutor, scheduler,
        clock, new SecureRandom());
    var challenge = retryableService.createChallenge(SHUTDOWN_COMPUTER);
    retryableService.execute(
        new ActionConfirmation(
            challenge.id(), SHUTDOWN_COMPUTER, PASSWORD, "SHUTDOWN COMPUTER"),
        request);

    assertThatThrownBy(() -> retryableService.cancel(request))
        .isInstanceOf(InvalidRequestException.class);
    assertThat(retryableService.pendingAction()).isPresent();

    failCancellation.set(false);
    assertThat(retryableService.cancel(request).accepted()).isTrue();
    assertThat(retryableService.pendingAction()).isEmpty();
  }

  @Test
  void failedPowerLaunchDoesNotLeaveAPhantomPendingAction() throws Exception {
    CommandExecutor failingExecutor = action -> {
      throw new java.io.IOException("simulated fixed power launch failure");
    };
    var failingService = new CommandCenterActionService(
        properties, accounts, permissions, activities, clientIps, failingExecutor, scheduler,
        clock, new SecureRandom());
    var challenge = failingService.createChallenge(RESTART_COMPUTER);

    assertThatThrownBy(() -> failingService.execute(
        new ActionConfirmation(
            challenge.id(), RESTART_COMPUTER, PASSWORD, "RESTART COMPUTER"),
        request)).isInstanceOf(InvalidRequestException.class);

    assertThat(failingService.pendingAction()).isEmpty();
  }

  @Test
  void completionAuditFailureCannotMisreportAnAlreadyLaunchedHostAction() throws Exception {
    doThrow(new IllegalStateException("simulated audit persistence failure"))
        .when(activities).recordForActor(
            any(), any(), org.mockito.ArgumentMatchers.eq("COMMAND_CENTER_ACTION_LAUNCHED"),
            any(), any(), any(), any(), any());
    var challenge = service.createChallenge(RESTART_COMPUTER);

    var result = execute(
        challenge.id(), RESTART_COMPUTER, PASSWORD, "RESTART COMPUTER");

    assertThat(result.accepted()).isTrue();
    assertThat(executed).containsExactly(RESTART_COMPUTER);
  }

  @Test
  void pendingPowerActionRejectsCompetingMachineAction() throws Exception {
    var restartChallenge = service.createChallenge(RESTART_COMPUTER);
    execute(restartChallenge.id(), RESTART_COMPUTER, PASSWORD, "RESTART COMPUTER");
    var shutdownChallenge = service.createChallenge(SHUTDOWN_COMPUTER);

    assertThatThrownBy(() -> execute(
        shutdownChallenge.id(), SHUTDOWN_COMPUTER, PASSWORD, "SHUTDOWN COMPUTER"))
        .isInstanceOf(InvalidRequestException.class);

    assertThat(executed).containsExactly(RESTART_COMPUTER);
    assertThat(service.pendingAction()).get()
        .extracting("action")
        .isEqualTo("RESTART_COMPUTER");
  }

  @Test
  void disabledServiceRejectsChallengesAndActions() throws Exception {
    properties.setEnabled(false);
    assertThatThrownBy(() -> service.createChallenge(RESTART_SITE))
        .isInstanceOf(InvalidRequestException.class);
    verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    assertThat(executed).isEmpty();
  }

  private CommandCenterActionService.ActionResult execute(
      String challengeId, CommandCenterActionType action, String password, String phrase)
      throws Exception {
    return service.execute(new ActionConfirmation(challengeId, action, password, phrase), request);
  }

  private static Account account(String id, Role role, AccountStatus status, boolean approved) {
    try {
      var salt = PasswordUtil.generateSalt();
      return Account.builder()
          .id(id)
          .username(id.equals("admin-1") ? "admin" : id)
          .role(role)
          .status(status)
          .isApproved(approved)
          .passwordSalt(salt)
          .passwordHash(PasswordUtil.hashPassword(PASSWORD, salt))
          .build();
    } catch (java.security.GeneralSecurityException exception) {
      throw new AssertionError("Unable to build password fixture", exception);
    }
  }

  private static CommandCenterProperties properties() {
    var properties = new CommandCenterProperties();
    properties.setEnabled(true);
    properties.getActions().setMode(CommandCenterProperties.ActionMode.SIMULATED);
    properties.getActions().setChallengeTtl(Duration.ofMinutes(2));
    properties.getActions().setFailedAttempts(3);
    properties.getActions().setFailedAttemptWindow(Duration.ofMinutes(15));
    properties.getActions().setCooldown(Duration.ofMinutes(2));
    properties.getActions().setPowerDelay(Duration.ofSeconds(60));
    return properties;
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
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
      return instant;
    }
  }

  private static final class CooldownRaceClock extends Clock {
    private final Instant instant;
    private final java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.CountDownLatch acceptanceBarrier =
        new java.util.concurrent.CountDownLatch(2);

    private CooldownRaceClock(Instant instant) {
      this.instant = instant;
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
      int call = calls.incrementAndGet();
      if (call == 9 || call == 10) {
        acceptanceBarrier.countDown();
        try {
          if (!acceptanceBarrier.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Concurrent executions did not reach the acceptance barrier");
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Interrupted while coordinating cooldown race", exception);
        }
      }
      return instant;
    }
  }
}
