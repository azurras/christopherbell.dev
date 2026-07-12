package dev.christopherbell.admin.commandcenter.action;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.admin.activity.AdminActivityService;
import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.PendingAction;
import dev.christopherbell.configuration.ClientIpResolver;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.security.PasswordUtil;
import dev.christopherbell.permission.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Protects fixed host actions with fresh account checks and one-time challenges. */
@Slf4j
@Service
public class CommandCenterActionService {
  private static final Duration WEBSITE_RESTART_RESPONSE_GRACE = Duration.ofSeconds(2);
  private static final Duration MACHINE_POWER_DELAY = Duration.ofSeconds(60);
  private static final int MAX_CHALLENGES_PER_ACTOR = 8;
  private static final int MAX_CHALLENGES_TOTAL = 64;

  private final CommandCenterProperties properties;
  private final AccountRepository accountRepository;
  private final PermissionService permissionService;
  private final AdminActivityService adminActivityService;
  private final ClientIpResolver clientIpResolver;
  private final CommandExecutor commandExecutor;
  private final TaskScheduler scheduler;
  private final Clock clock;
  private final SecureRandom secureRandom;
  private final ConcurrentHashMap<String, StoredChallenge> challenges = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ArrayDeque<Instant>> failedAttempts = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<ActionKey, Instant> acceptedActions = new ConcurrentHashMap<>();
  private final AtomicReference<PendingAction> pendingAction = new AtomicReference<>();
  private final Object actionStateLock = new Object();
  private final Object challengeStoreLock = new Object();

  public CommandCenterActionService(
      CommandCenterProperties properties,
      AccountRepository accountRepository,
      PermissionService permissionService,
      AdminActivityService adminActivityService,
      ClientIpResolver clientIpResolver,
      CommandExecutor commandExecutor,
      @Qualifier("commandCenterActionScheduler") TaskScheduler scheduler,
      Clock clock) {
    this(properties, accountRepository, permissionService, adminActivityService, clientIpResolver,
        commandExecutor, scheduler, clock, new SecureRandom());
  }

  CommandCenterActionService(
      CommandCenterProperties properties,
      AccountRepository accountRepository,
      PermissionService permissionService,
      AdminActivityService adminActivityService,
      ClientIpResolver clientIpResolver,
      CommandExecutor commandExecutor,
      TaskScheduler scheduler,
      Clock clock,
      SecureRandom secureRandom) {
    this.properties = properties;
    this.accountRepository = accountRepository;
    this.permissionService = permissionService;
    this.adminActivityService = adminActivityService;
    this.clientIpResolver = clientIpResolver;
    this.commandExecutor = commandExecutor;
    this.scheduler = scheduler;
    this.clock = clock;
    this.secureRandom = secureRandom;
  }

  /** Creates a short-lived challenge bound to the current admin and requested action. */
  public ActionChallenge createChallenge(CommandCenterActionType action)
      throws InvalidRequestException {
    return createChallenge(action, null);
  }

  /** Creates and audits a challenge using the request's resolved source address. */
  public ActionChallenge createChallenge(
      CommandCenterActionType action, HttpServletRequest servletRequest)
      throws InvalidRequestException {
    requireEnabled();
    var actor = requireCurrentAdmin();
    var clientIp = clientIpResolver.resolveClientIp(servletRequest);
    if (action == null || !action.isRequiresChallenge()) {
      auditChallenge(actor, action, clientIp, "invalid-action", false);
      throw new InvalidRequestException("Action does not use a challenge.");
    }
    if (isPowerAction(action) && !properties.getActions().isPowerActionsEnabled()) {
      auditChallenge(actor, action, clientIp, "power-actions-disabled", false);
      throw new InvalidRequestException("Computer power actions are disabled.");
    }
    try {
      enforceAttemptWindow(actor.getId());
    } catch (InvalidRequestException exception) {
      auditChallenge(actor, action, clientIp, "throttled", false);
      throw exception;
    }
    var now = clock.instant();
    var expiresAt = now.plus(properties.getActions().getChallengeTtl());
    var challenge = new StoredChallenge(randomToken(), actor.getId(), action, expiresAt);
    synchronized (challengeStoreLock) {
      pruneExpiredChallenges(now);
      evictOldestChallengesForActor(actor.getId());
      evictOldestChallengesToTotalBound();
      challenges.put(challenge.id(), challenge);
    }
    auditChallenge(actor, action, clientIp, "created", true);
    return new ActionChallenge(
        challenge.id(), action, expiresAt, action.getConfirmationPhrase());
  }

  /** Atomically consumes a challenge and accepts its fixed action once. */
  public ActionResult execute(ActionConfirmation confirmation, HttpServletRequest servletRequest)
      throws InvalidRequestException {
    requireEnabled();
    if (confirmation == null) {
      throw new InvalidRequestException("Action confirmation is required.");
    }
    var actor = requireCurrentAdmin();
    var clientIp = clientIpResolver.resolveClientIp(servletRequest);
    try {
      validateConfirmation(actor, confirmation);
    } catch (InvalidRequestException exception) {
      auditRejection(actor, confirmation.action(), clientIp, rejectionCategory(exception));
      throw exception;
    }
    var now = clock.instant();
    var executeAt = executionTime(confirmation.action(), now);

    if (isPowerAction(confirmation.action())) {
      synchronized (actionStateLock) {
        PendingAction acceptedPendingAction;
        try {
          acceptedPendingAction = acceptActionState(
              actor.getId(), confirmation.action(), now, executeAt);
        } catch (InvalidRequestException exception) {
          auditRejection(actor, confirmation.action(), clientIp, rejectionCategory(exception));
          throw exception;
        }
        try {
          audit(actor, confirmation.action(), clientIp, "accepted");
          executeNow(actor, confirmation.action(), clientIp);
        } catch (InvalidRequestException | RuntimeException exception) {
          rollbackActionState(actor.getId(), confirmation.action(), now, acceptedPendingAction);
          throw exception;
        }
      }
    } else {
      try {
        acceptActionState(actor.getId(), confirmation.action(), now, executeAt);
      } catch (InvalidRequestException exception) {
        auditRejection(actor, confirmation.action(), clientIp, rejectionCategory(exception));
        throw exception;
      }
      try {
        audit(actor, confirmation.action(), clientIp, "accepted");
        scheduler.schedule(
            () -> executeBackground(actor, confirmation.action(), clientIp), executeAt);
      } catch (RuntimeException exception) {
        rollbackActionState(actor.getId(), confirmation.action(), now, null);
        throw exception;
      }
    }
    return new ActionResult(confirmation.action(), true, now, executeAt);
  }

  /** Cancels the currently pending machine power action using the fixed shutdown /a command. */
  public ActionResult cancel(HttpServletRequest servletRequest) throws InvalidRequestException {
    requireEnabled();
    var actor = requireCurrentAdmin();
    var clientIp = clientIpResolver.resolveClientIp(servletRequest);
    synchronized (actionStateLock) {
      var current = pendingAction.get();
      if (current == null || !current.cancellable()
          || !clock.instant().isBefore(current.executeAt())) {
        auditRejection(
            actor, CommandCenterActionType.CANCEL_PENDING_ACTION, clientIp, "no-pending-action");
        throw new InvalidRequestException("There is no cancellable pending action.");
      }
      audit(actor, CommandCenterActionType.CANCEL_PENDING_ACTION, clientIp, "accepted");
      executeNow(actor, CommandCenterActionType.CANCEL_PENDING_ACTION, clientIp);
      pendingAction.compareAndSet(current, null);
      var acceptedAt = clock.instant();
      return new ActionResult(
          CommandCenterActionType.CANCEL_PENDING_ACTION, true, acceptedAt, acceptedAt);
    }
  }

  /** Returns only the immutable pending power-action snapshot. */
  public Optional<PendingAction> pendingAction() {
    var current = pendingAction.get();
    if (current != null && !clock.instant().isBefore(current.executeAt())) {
      pendingAction.compareAndSet(current, null);
      current = pendingAction.get();
    }
    return Optional.ofNullable(current);
  }

  private void requireEnabled() throws InvalidRequestException {
    if (!properties.isEnabled()) {
      throw new InvalidRequestException("Command center is disabled.");
    }
  }

  private Account requireCurrentAdmin() throws InvalidRequestException {
    var actorId = permissionService.getSelfId();
    var actor = accountRepository.findById(actorId)
        .orElseThrow(() -> new InvalidRequestException("Current account is unavailable."));
    if (actor.getRole() != Role.ADMIN
        || actor.getStatus() != AccountStatus.ACTIVE
        || !Boolean.TRUE.equals(actor.getIsApproved())) {
      throw new InvalidRequestException("An active approved administrator account is required.");
    }
    return actor;
  }

  private void consumeChallenge(ActionConfirmation confirmation, String actorId)
      throws InvalidRequestException {
    if (confirmation.challengeId() == null || confirmation.action() == null
        || !confirmation.action().isRequiresChallenge()) {
      throw new InvalidRequestException("A valid action challenge is required.");
    }
    StoredChallenge challenge;
    synchronized (challengeStoreLock) {
      pruneExpiredChallenges(clock.instant());
      challenge = challenges.remove(confirmation.challengeId());
    }
    if (challenge == null
        || !challenge.actorId().equals(actorId)
        || challenge.action() != confirmation.action()
        || !clock.instant().isBefore(challenge.expiresAt())) {
      throw new InvalidRequestException("Action challenge is invalid or expired.");
    }
  }

  private void verifyPassword(Account actor, String password) throws InvalidRequestException {
    try {
      if (password == null || !PasswordUtil.verifyPassword(
          password, actor.getPasswordSalt(), actor.getPasswordHash())) {
        throw new InvalidRequestException("Password verification failed.");
      }
    } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException exception) {
      throw new InvalidRequestException("Password verification failed.");
    }
  }

  private void validateConfirmation(Account actor, ActionConfirmation confirmation)
      throws InvalidRequestException {
    var attempts = failedAttempts.computeIfAbsent(actor.getId(), ignored -> new ArrayDeque<>());
    synchronized (attempts) {
      prune(attempts, properties.getActions().getFailedAttemptWindow());
      if (attempts.size() >= properties.getActions().getFailedAttempts()) {
        throw new InvalidRequestException("Too many failed action confirmations.");
      }
      try {
        consumeChallenge(confirmation, actor.getId());
        verifyPassword(actor, confirmation.password());
        if (!confirmation.action().getConfirmationPhrase()
            .equals(confirmation.confirmationPhrase())) {
          throw new InvalidRequestException("Confirmation phrase did not match.");
        }
      } catch (InvalidRequestException exception) {
        attempts.addLast(clock.instant());
        throw exception;
      }
    }
  }

  private void enforceAttemptWindow(String actorId) throws InvalidRequestException {
    var attempts = failedAttempts.computeIfAbsent(actorId, ignored -> new ArrayDeque<>());
    synchronized (attempts) {
      prune(attempts, properties.getActions().getFailedAttemptWindow());
      if (attempts.size() >= properties.getActions().getFailedAttempts()) {
        throw new InvalidRequestException("Too many failed action confirmations.");
      }
    }
  }

  private void prune(ArrayDeque<Instant> attempts, Duration window) {
    var cutoff = clock.instant().minus(window);
    while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(cutoff)) {
      attempts.removeFirst();
    }
  }

  private PendingAction acceptActionState(
      String actorId, CommandCenterActionType action, Instant acceptedAt, Instant executeAt)
      throws InvalidRequestException {
    synchronized (actionStateLock) {
      if (isPowerAction(action)) {
        var current = pendingAction.get();
        if (current != null && acceptedAt.isBefore(current.executeAt())) {
          throw new InvalidRequestException("A machine power action is already pending.");
        }
      }
      var actionKey = new ActionKey(actorId, action);
      var previousAcceptance = acceptedActions.get(actionKey);
      if (previousAcceptance != null
          && acceptedAt.isBefore(
              previousAcceptance.plus(properties.getActions().getCooldown()))) {
        throw new InvalidRequestException("Action is in cooldown.");
      }
      acceptedActions.put(actionKey, acceptedAt);
      if (!isPowerAction(action)) {
        return null;
      }
      var acceptedPendingAction = new PendingAction(action.name(), executeAt, true);
      pendingAction.set(acceptedPendingAction);
      return acceptedPendingAction;
    }
  }

  private void rollbackActionState(
      String actorId,
      CommandCenterActionType action,
      Instant acceptedAt,
      PendingAction acceptedPendingAction) {
    acceptedActions.remove(new ActionKey(actorId, action), acceptedAt);
    pendingAction.compareAndSet(acceptedPendingAction, null);
  }

  private Instant executionTime(CommandCenterActionType action, Instant now) {
    if (isPowerAction(action)) {
      return now.plus(MACHINE_POWER_DELAY);
    }
    return now.plus(WEBSITE_RESTART_RESPONSE_GRACE);
  }

  private boolean isPowerAction(CommandCenterActionType action) {
    return action == CommandCenterActionType.RESTART_COMPUTER
        || action == CommandCenterActionType.SHUTDOWN_COMPUTER;
  }

  private void executeNow(Account actor, CommandCenterActionType action, String clientIp)
      throws InvalidRequestException {
    try {
      commandExecutor.execute(action);
    } catch (Exception exception) {
      auditSafely(actor, action, clientIp, "launch-failed");
      throw new InvalidRequestException("The fixed host action could not be launched.");
    }
    auditSafely(actor, action, clientIp, "launched");
  }

  private void executeBackground(Account actor, CommandCenterActionType action, String clientIp) {
    try {
      executeNow(actor, action, clientIp);
    } catch (InvalidRequestException exception) {
      log.error("Deferred fixed command-center action failed to launch: {}", action);
    }
  }

  private void auditSafely(
      Account actor, CommandCenterActionType action, String clientIp, String outcome) {
    try {
      audit(actor, action, clientIp, outcome);
    } catch (RuntimeException exception) {
      log.error("Unable to record command-center completion audit for {}: {}",
          action, outcome, exception);
    }
  }

  private void audit(
      Account actor, CommandCenterActionType action, String clientIp, String outcome) {
    adminActivityService.recordForActor(
        actor.getId(), actor.getUsername() == null ? actor.getId() : actor.getUsername(),
        "COMMAND_CENTER_ACTION_" + outcome.toUpperCase().replace('-', '_'),
        "command-center", action.name(), action.name(),
        "%s requested a protected command-center action.",
        Map.of(
            "action", action.name(),
            "clientIp", clientIp == null ? "unknown" : clientIp,
            "mode", properties.getActions().getMode().name(),
            "outcome", outcome));
  }

  private void auditRejection(
      Account actor, CommandCenterActionType action, String clientIp, String outcome) {
    auditEvent(actor, action, clientIp, outcome, "COMMAND_CENTER_ACTION_REJECTED");
  }

  private void auditChallenge(
      Account actor,
      CommandCenterActionType action,
      String clientIp,
      String outcome,
      boolean created) {
    auditEvent(actor, action, clientIp, outcome,
        created ? "COMMAND_CENTER_CHALLENGE_CREATED" : "COMMAND_CENTER_CHALLENGE_REJECTED");
  }

  private void auditEvent(
      Account actor,
      CommandCenterActionType action,
      String clientIp,
      String outcome,
      String event) {
    String safeAction = action == null ? "UNKNOWN" : action.name();
    adminActivityService.recordForActor(
        actor.getId(), actor.getUsername() == null ? actor.getId() : actor.getUsername(),
        event, "command-center", safeAction, safeAction,
        "%s requested a protected command-center operation.",
        Map.of(
            "action", safeAction,
            "clientIp", clientIp == null ? "unknown" : clientIp,
            "mode", properties.getActions().getMode().name(),
            "outcome", outcome));
  }

  private static String rejectionCategory(InvalidRequestException exception) {
    return switch (exception.getMessage()) {
      case "Password verification failed." -> "wrong-password";
      case "Confirmation phrase did not match." -> "phrase-mismatch";
      case "Too many failed action confirmations." -> "throttled";
      case "Action is in cooldown." -> "cooldown";
      case "A machine power action is already pending." -> "action-pending";
      default -> "invalid-challenge";
    };
  }

  private void pruneExpiredChallenges(Instant now) {
    challenges.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
  }

  private void evictOldestChallengesForActor(String actorId) {
    while (challenges.values().stream()
        .filter(challenge -> challenge.actorId().equals(actorId))
        .count() >= MAX_CHALLENGES_PER_ACTOR) {
      removeOldestChallenge(actorId);
    }
  }

  private void evictOldestChallengesToTotalBound() {
    while (challenges.size() >= MAX_CHALLENGES_TOTAL) {
      removeOldestChallenge(null);
    }
  }

  private void removeOldestChallenge(String actorId) {
    challenges.values().stream()
        .filter(challenge -> actorId == null || challenge.actorId().equals(actorId))
        .min(java.util.Comparator.comparing(StoredChallenge::expiresAt)
            .thenComparing(StoredChallenge::id))
        .ifPresent(challenge -> challenges.remove(challenge.id()));
  }

  private String randomToken() {
    var bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Public challenge response safe to send to the browser. */
  public record ActionChallenge(
      String id,
      CommandCenterActionType action,
      Instant expiresAt,
      String confirmationPhrase) {
    @Override
    public String toString() {
      return "ActionChallenge[id=<redacted>, action=" + action
          + ", expiresAt=" + expiresAt + ", confirmationPhrase=" + confirmationPhrase + "]";
    }
  }

  /** Confirmation input; no field can influence an executable or argument. */
  public record ActionConfirmation(
      @NotBlank String challengeId,
      @NotNull CommandCenterActionType action,
      @NotBlank String password,
      @NotBlank String confirmationPhrase) {
    @Override
    public String toString() {
      return "ActionConfirmation[action=" + action
          + ", challengeId=<redacted>, password=<redacted>, confirmationPhrase=<redacted>]";
    }
  }

  /** Acceptance response for a fixed action. */
  public record ActionResult(
      CommandCenterActionType action,
      boolean accepted,
      Instant acceptedAt,
      Instant executeAt) {}

  private record StoredChallenge(
      String id,
      String actorId,
      CommandCenterActionType action,
      Instant expiresAt) {
    @Override
    public String toString() {
      return "StoredChallenge[id=<redacted>, actorId=" + actorId
          + ", action=" + action + ", expiresAt=" + expiresAt + "]";
    }
  }

  private record ActionKey(String actorId, CommandCenterActionType action) {}
}
