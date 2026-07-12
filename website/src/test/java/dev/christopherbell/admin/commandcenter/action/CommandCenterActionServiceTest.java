package dev.christopherbell.admin.commandcenter.action;

import static dev.christopherbell.admin.commandcenter.action.CommandCenterActionType.RESTART_COMPUTER;
import static dev.christopherbell.admin.commandcenter.action.CommandCenterActionType.RESTART_SITE;
import static dev.christopherbell.admin.commandcenter.action.CommandCenterActionType.SHUTDOWN_COMPUTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
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
    var concurrentExecutions = java.util.Collections.synchronizedList(
        new ArrayList<CommandCenterActionType>());
    var concurrentService = new CommandCenterActionService(
        properties, accounts, permissions, activities, clientIps, concurrentExecutions::add,
        scheduler, clock, new SecureRandom());
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
  void challengeStringRepresentationsNeverExposeTheOneTimeId() throws Exception {
    var challenge = service.createChallenge(RESTART_SITE);
    var stored = storedChallenges(service).get(challenge.id());

    assertThat(challenge.toString())
        .doesNotContain(challenge.id())
        .contains("<redacted>");
    assertThat(stored.toString())
        .doesNotContain(challenge.id())
        .contains("<redacted>");
  }

  @Test
  void challengeStorePrunesExpiredEntriesAndCapsPerActorAndTotalSize() throws Exception {
    service.createChallenge(RESTART_SITE);
    clock.advance(Duration.ofMinutes(2).plusMillis(1));
    service.createChallenge(RESTART_SITE);
    assertThat(storedChallenges(service)).hasSize(1);

    for (int challenge = 0; challenge < 20; challenge++) {
      service.createChallenge(RESTART_SITE);
    }
    assertThat(storedChallenges(service).size()).isLessThanOrEqualTo(8);

    var currentActor = new java.util.concurrent.atomic.AtomicReference<>("bounded-admin-0");
    when(permissions.getSelfId()).thenAnswer(ignored -> currentActor.get());
    when(accounts.findById(any())).thenAnswer(invocation -> {
      String id = invocation.getArgument(0);
      return Optional.of(Account.builder()
          .id(id).username(id).role(Role.ADMIN).status(AccountStatus.ACTIVE).isApproved(true)
          .passwordSalt(actor.getPasswordSalt()).passwordHash(actor.getPasswordHash()).build());
    });
    var boundedService = new CommandCenterActionService(
        properties, accounts, permissions, activities, clientIps, executor, scheduler,
        clock, new SecureRandom());
    for (int accountIndex = 0; accountIndex < 65; accountIndex++) {
      currentActor.set("bounded-admin-" + accountIndex);
      boundedService.createChallenge(RESTART_SITE);
    }
    assertThat(storedChallenges(boundedService).size()).isLessThanOrEqualTo(64);
  }

  @Test
  void concurrentInvalidChallengesAtomicallyReserveOnlyThreeFailureSlots() throws Exception {
    alignAccountLookups(32);
    var messages = runConcurrently(32, attempt -> new ActionConfirmation(
        "missing-" + attempt, RESTART_SITE, PASSWORD, "RESTART SITE"));

    assertThat(messages.stream()
        .filter("Action challenge is invalid or expired."::equals)
        .count()).isEqualTo(3);
    assertThat(messages.stream()
        .filter("Too many failed action confirmations."::equals)
        .count()).isEqualTo(29);
  }

  @Test
  void concurrentWrongPasswordsAtomicallyReserveOnlyThreeFailureSlots() throws Exception {
    var confirmations = new ArrayList<ActionConfirmation>();
    for (int attempt = 0; attempt < 8; attempt++) {
      var challenge = service.createChallenge(RESTART_SITE);
      confirmations.add(new ActionConfirmation(
          challenge.id(), RESTART_SITE, "wrong-password-" + attempt, "RESTART SITE"));
    }
    alignAccountLookups(8);

    var messages = runConcurrently(confirmations);

    assertThat(messages.stream()
        .filter("Password verification failed."::equals)
        .count()).isEqualTo(3);
    assertThat(messages.stream()
        .filter("Too many failed action confirmations."::equals)
        .count()).isEqualTo(5);
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
  void rejectedConfirmationsAreDurablyAuditedWithSafeCategoriesAndSourceIp() throws Exception {
    properties.getActions().setFailedAttempts(20);

    var wrongPassword = service.createChallenge(RESTART_SITE, request);
    assertThatThrownBy(() -> execute(
        wrongPassword.id(), RESTART_SITE, "wrong", "RESTART SITE"))
        .isInstanceOf(InvalidRequestException.class);
    var phraseMismatch = service.createChallenge(RESTART_SITE, request);
    assertThatThrownBy(() -> execute(
        phraseMismatch.id(), RESTART_SITE, PASSWORD, "wrong phrase"))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> execute(
        "missing", RESTART_SITE, PASSWORD, "RESTART SITE"))
        .isInstanceOf(InvalidRequestException.class);
    var expired = service.createChallenge(RESTART_SITE, request);
    clock.advance(Duration.ofMinutes(2).plusMillis(1));
    assertThatThrownBy(() -> execute(
        expired.id(), RESTART_SITE, PASSWORD, "RESTART SITE"))
        .isInstanceOf(InvalidRequestException.class);
    var replayed = service.createChallenge(RESTART_SITE, request);
    execute(replayed.id(), RESTART_SITE, PASSWORD, "RESTART SITE");
    assertThatThrownBy(() -> execute(
        replayed.id(), RESTART_SITE, PASSWORD, "RESTART SITE"))
        .isInstanceOf(InvalidRequestException.class);

    @SuppressWarnings("unchecked")
    var metadata = ArgumentCaptor.forClass(
        (Class<java.util.Map<String, String>>) (Class<?>) java.util.Map.class);
    verify(activities, org.mockito.Mockito.atLeast(5)).recordForActor(
        eq("admin-1"), eq("admin"), eq("COMMAND_CENTER_ACTION_REJECTED"),
        eq("command-center"), eq("RESTART_SITE"), eq("RESTART_SITE"), any(), metadata.capture());
    assertThat(metadata.getAllValues()).extracting(value -> value.get("outcome"))
        .contains("wrong-password", "phrase-mismatch", "invalid-challenge");
    assertThat(metadata.getAllValues()).allSatisfy(value -> {
      assertThat(value).containsEntry("clientIp", "203.0.113.9");
      assertThat(value).containsOnlyKeys("action", "clientIp", "mode", "outcome");
      assertThat(value.toString()).doesNotContain(
          PASSWORD, "wrong phrase", wrongPassword.id(), expired.id(), replayed.id());
    });
  }

  @Test
  void challengeCreationAndThrottleAreAuditedWithoutChallengeIdentifiers() throws Exception {
    properties.getActions().setFailedAttempts(1);
    var challenge = service.createChallenge(RESTART_SITE, request);
    assertThatThrownBy(() -> execute(challenge.id(), RESTART_SITE, "wrong", "RESTART SITE"))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> service.createChallenge(RESTART_SITE, request))
        .isInstanceOf(InvalidRequestException.class);

    @SuppressWarnings("unchecked")
    var metadata = ArgumentCaptor.forClass(
        (Class<java.util.Map<String, String>>) (Class<?>) java.util.Map.class);
    verify(activities).recordForActor(
        eq("admin-1"), eq("admin"), eq("COMMAND_CENTER_CHALLENGE_CREATED"),
        eq("command-center"), eq("RESTART_SITE"), eq("RESTART_SITE"), any(), metadata.capture());
    verify(activities).recordForActor(
        eq("admin-1"), eq("admin"), eq("COMMAND_CENTER_CHALLENGE_REJECTED"),
        eq("command-center"), eq("RESTART_SITE"), eq("RESTART_SITE"), any(), metadata.capture());
    assertThat(metadata.getAllValues()).allSatisfy(value -> {
      assertThat(value).containsEntry("clientIp", "203.0.113.9");
      assertThat(value.toString()).doesNotContain(challenge.id(), PASSWORD, "challengeId");
    });
  }

  @Test
  void invalidChallengeTypeAndCancelWithoutPendingActionAreAudited() throws Exception {
    assertThatThrownBy(() -> service.createChallenge(
        CommandCenterActionType.CANCEL_PENDING_ACTION, request))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> service.cancel(request))
        .isInstanceOf(InvalidRequestException.class);

    verify(activities).recordForActor(
        eq("admin-1"), eq("admin"), eq("COMMAND_CENTER_CHALLENGE_REJECTED"),
        eq("command-center"), eq("CANCEL_PENDING_ACTION"), eq("CANCEL_PENDING_ACTION"),
        any(), any());
    verify(activities).recordForActor(
        eq("admin-1"), eq("admin"), eq("COMMAND_CENTER_ACTION_REJECTED"),
        eq("command-center"), eq("CANCEL_PENDING_ACTION"), eq("CANCEL_PENDING_ACTION"),
        any(), any());
  }

  @Test
  void powerActionExecuteAtRemainsExactlySixtySecondsDespitePropertyOverride() throws Exception {
    properties.getActions().setPowerActionsEnabled(true);
    properties.getActions().setPowerDelay(Duration.ofSeconds(5));
    var challenge = service.createChallenge(RESTART_COMPUTER);

    var result = execute(
        challenge.id(), RESTART_COMPUTER, PASSWORD, "RESTART COMPUTER");

    assertThat(result.executeAt()).isEqualTo(NOW.plusSeconds(60));
  }

  @Test
  void powerActionsRequireDedicatedEnableFlagEvenInWindowsMode() throws Exception {
    properties.getActions().setMode(CommandCenterProperties.ActionMode.WINDOWS);
    properties.getActions().setPowerActionsEnabled(false);

    assertThatThrownBy(() -> service.createChallenge(RESTART_COMPUTER, request))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("disabled");
    assertThat(service.createChallenge(RESTART_SITE, request).action()).isEqualTo(RESTART_SITE);
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
  void cancellationAuditIsPersistedBeforeTheFixedCancelCommand() throws Exception {
    var orderedExecutor = mock(CommandExecutor.class);
    var orderedService = new CommandCenterActionService(
        properties, accounts, permissions, activities, clientIps, orderedExecutor, scheduler,
        clock, new SecureRandom());
    var challenge = orderedService.createChallenge(SHUTDOWN_COMPUTER);
    orderedService.execute(new ActionConfirmation(
        challenge.id(), SHUTDOWN_COMPUTER, PASSWORD, "SHUTDOWN COMPUTER"), request);
    clearInvocations(activities, orderedExecutor);

    orderedService.cancel(request);

    var order = inOrder(activities, orderedExecutor);
    order.verify(activities).recordForActor(
        any(), any(), eq("COMMAND_CENTER_ACTION_ACCEPTED"), any(),
        eq("CANCEL_PENDING_ACTION"), any(), any(), any());
    order.verify(orderedExecutor).execute(CommandCenterActionType.CANCEL_PENDING_ACTION);
  }

  @Test
  void failedCancellationAuditsFailureAfterAcceptanceAndRetainsPendingState() throws Exception {
    var orderedExecutor = mock(CommandExecutor.class);
    var orderedService = new CommandCenterActionService(
        properties, accounts, permissions, activities, clientIps, orderedExecutor, scheduler,
        clock, new SecureRandom());
    var challenge = orderedService.createChallenge(SHUTDOWN_COMPUTER);
    orderedService.execute(new ActionConfirmation(
        challenge.id(), SHUTDOWN_COMPUTER, PASSWORD, "SHUTDOWN COMPUTER"), request);
    clearInvocations(activities, orderedExecutor);
    doThrow(new java.io.IOException("simulated cancel failure"))
        .when(orderedExecutor).execute(CommandCenterActionType.CANCEL_PENDING_ACTION);

    assertThatThrownBy(() -> orderedService.cancel(request))
        .isInstanceOf(InvalidRequestException.class);

    var order = inOrder(activities, orderedExecutor);
    order.verify(activities).recordForActor(
        any(), any(), eq("COMMAND_CENTER_ACTION_ACCEPTED"), any(),
        eq("CANCEL_PENDING_ACTION"), any(), any(), any());
    order.verify(orderedExecutor).execute(CommandCenterActionType.CANCEL_PENDING_ACTION);
    order.verify(activities).recordForActor(
        any(), any(), eq("COMMAND_CENTER_ACTION_LAUNCH_FAILED"), any(),
        eq("CANCEL_PENDING_ACTION"), any(), any(), any());
    assertThat(orderedService.pendingAction()).isPresent();
  }

  @Test
  void cancellationCannotOvertakeAReservedPowerActionBeforeItsLaunch() throws Exception {
    var powerEntered = new java.util.concurrent.CountDownLatch(1);
    var releasePower = new java.util.concurrent.CountDownLatch(1);
    var cancelEntered = new java.util.concurrent.CountDownLatch(1);
    var done = new java.util.concurrent.CountDownLatch(2);
    var launchOrder = java.util.Collections.synchronizedList(
        new ArrayList<CommandCenterActionType>());
    CommandExecutor blockingExecutor = action -> {
      if (action == RESTART_COMPUTER) {
        powerEntered.countDown();
        try {
          if (!releasePower.await(5, TimeUnit.SECONDS)) {
            throw new java.io.IOException("timed out waiting to release power launch");
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new java.io.IOException("interrupted", exception);
        }
      } else if (action == CommandCenterActionType.CANCEL_PENDING_ACTION) {
        cancelEntered.countDown();
      }
      launchOrder.add(action);
    };
    var serializedService = new CommandCenterActionService(
        properties, accounts, permissions, activities, clientIps, blockingExecutor, scheduler,
        clock, new SecureRandom());
    var challenge = serializedService.createChallenge(RESTART_COMPUTER);

    Thread.startVirtualThread(() -> {
      try {
        serializedService.execute(new ActionConfirmation(
            challenge.id(), RESTART_COMPUTER, PASSWORD, "RESTART COMPUTER"), request);
      } catch (Exception ignored) {
        // Asserted through ordering below.
      } finally {
        done.countDown();
      }
    });
    assertThat(powerEntered.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.startVirtualThread(() -> {
      try {
        serializedService.cancel(request);
      } catch (Exception ignored) {
        // Asserted through ordering below.
      } finally {
        done.countDown();
      }
    });

    assertThat(cancelEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
    releasePower.countDown();
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(launchOrder).containsExactly(
        RESTART_COMPUTER, CommandCenterActionType.CANCEL_PENDING_ACTION);
  }

  @Test
  void failedPowerLaunchDoesNotLeaveAPhantomPendingAction() throws Exception {
    var failLaunch = new java.util.concurrent.atomic.AtomicBoolean(true);
    CommandExecutor failingExecutor = action -> {
      if (failLaunch.get()) {
        throw new java.io.IOException("simulated fixed power launch failure");
      }
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
    failLaunch.set(false);
    var retry = failingService.createChallenge(RESTART_COMPUTER);
    assertThat(failingService.execute(new ActionConfirmation(
        retry.id(), RESTART_COMPUTER, PASSWORD, "RESTART COMPUTER"), request).accepted()).isTrue();
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

  private List<String> runConcurrently(
      int attempts,
      java.util.function.IntFunction<ActionConfirmation> confirmationFactory) throws Exception {
    var confirmations = new ArrayList<ActionConfirmation>();
    for (int attempt = 0; attempt < attempts; attempt++) {
      confirmations.add(confirmationFactory.apply(attempt));
    }
    return runConcurrently(confirmations);
  }

  private List<String> runConcurrently(List<ActionConfirmation> confirmations) throws Exception {
    var messages = java.util.Collections.synchronizedList(new ArrayList<String>());
    var start = new java.util.concurrent.CountDownLatch(1);
    var done = new java.util.concurrent.CountDownLatch(confirmations.size());
    for (var confirmation : confirmations) {
      Thread.startVirtualThread(() -> {
        try {
          start.await();
          service.execute(confirmation, request);
        } catch (InvalidRequestException exception) {
          messages.add(exception.getMessage());
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          messages.add("interrupted");
        } finally {
          done.countDown();
        }
      });
    }
    start.countDown();
    assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
    return messages;
  }

  private void alignAccountLookups(int participants) {
    var actorLookups = new java.util.concurrent.CountDownLatch(participants);
    when(accounts.findById(actor.getId())).thenAnswer(ignored -> {
      actorLookups.countDown();
      try {
        if (!actorLookups.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("Concurrent account lookups did not align");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while aligning account lookups", exception);
      }
      return Optional.of(actor);
    });
  }

  @SuppressWarnings("unchecked")
  private static java.util.Map<String, ?> storedChallenges(CommandCenterActionService target)
      throws Exception {
    var field = CommandCenterActionService.class.getDeclaredField("challenges");
    field.setAccessible(true);
    return (java.util.Map<String, ?>) field.get(target);
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
    properties.getActions().setPowerActionsEnabled(true);
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

}
