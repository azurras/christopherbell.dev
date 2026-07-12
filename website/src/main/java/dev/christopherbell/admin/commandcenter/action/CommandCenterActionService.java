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
import org.springframework.stereotype.Service;

/** Protects fixed host actions with fresh account checks and one-time challenges. */
@Slf4j
@Service
public class CommandCenterActionService {
  private static final Duration WEBSITE_RESTART_RESPONSE_GRACE = Duration.ofSeconds(2);

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

  public CommandCenterActionService(
      CommandCenterProperties properties,
      AccountRepository accountRepository,
      PermissionService permissionService,
      AdminActivityService adminActivityService,
      ClientIpResolver clientIpResolver,
      CommandExecutor commandExecutor,
      TaskScheduler scheduler,
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
    requireEnabled();
    var actor = requireCurrentAdmin();
    if (action == null || !action.isRequiresChallenge()) {
      throw new InvalidRequestException("Action does not use a challenge.");
    }
    enforceAttemptWindow(actor.getId());
    var expiresAt = clock.instant().plus(properties.getActions().getChallengeTtl());
    var challenge = new StoredChallenge(randomToken(), actor.getId(), action, expiresAt);
    challenges.put(challenge.id(), challenge);
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
    enforceAttemptWindow(actor.getId());
    try {
      consumeChallenge(confirmation, actor.getId());
    } catch (InvalidRequestException exception) {
      recordFailedAttempt(actor.getId());
      throw exception;
    }
    verifyPassword(actor, confirmation.password());
    if (!confirmation.action().getConfirmationPhrase().equals(confirmation.confirmationPhrase())) {
      recordFailedAttempt(actor.getId());
      throw new InvalidRequestException("Confirmation phrase did not match.");
    }
    var now = clock.instant();
    var executeAt = executionTime(confirmation.action(), now);
    var acceptedPendingAction = acceptActionState(
        actor.getId(), confirmation.action(), now, executeAt);
    var clientIp = clientIpResolver.resolveClientIp(servletRequest);
    audit(actor, confirmation.action(), clientIp, "accepted");

    if (confirmation.action() == CommandCenterActionType.RESTART_SITE) {
      scheduler.schedule(
          () -> executeBackground(actor, confirmation.action(), clientIp), executeAt);
    } else {
      try {
        executeNow(actor, confirmation.action(), clientIp);
      } catch (InvalidRequestException exception) {
        if (acceptedPendingAction != null) {
          pendingAction.compareAndSet(acceptedPendingAction, null);
        }
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
      var current = pendingAction.getAndSet(null);
      if (current == null || !current.cancellable()
          || !clock.instant().isBefore(current.executeAt())) {
        throw new InvalidRequestException("There is no cancellable pending action.");
      }
      try {
        executeNow(actor, CommandCenterActionType.CANCEL_PENDING_ACTION, clientIp);
      } catch (InvalidRequestException exception) {
        pendingAction.compareAndSet(null, current);
        throw exception;
      }
    }
    audit(actor, CommandCenterActionType.CANCEL_PENDING_ACTION, clientIp, "accepted");
    return new ActionResult(
        CommandCenterActionType.CANCEL_PENDING_ACTION, true, clock.instant(), clock.instant());
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
    var consumed = new AtomicReference<StoredChallenge>();
    challenges.compute(confirmation.challengeId(), (ignored, stored) -> {
      consumed.set(stored);
      return null;
    });
    var challenge = consumed.get();
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
        recordFailedAttempt(actor.getId());
        throw new InvalidRequestException("Password verification failed.");
      }
    } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException exception) {
      recordFailedAttempt(actor.getId());
      throw new InvalidRequestException("Password verification failed.");
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

  private void recordFailedAttempt(String actorId) {
    var attempts = failedAttempts.computeIfAbsent(actorId, ignored -> new ArrayDeque<>());
    synchronized (attempts) {
      prune(attempts, properties.getActions().getFailedAttemptWindow());
      attempts.addLast(clock.instant());
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

  private Instant executionTime(CommandCenterActionType action, Instant now) {
    if (isPowerAction(action)) {
      return now.plus(properties.getActions().getPowerDelay());
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
      String confirmationPhrase) {}

  /** Confirmation input; no field can influence an executable or argument. */
  public record ActionConfirmation(
      String challengeId,
      CommandCenterActionType action,
      String password,
      String confirmationPhrase) {
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
      Instant expiresAt) {}

  private record ActionKey(String actorId, CommandCenterActionType action) {}
}
