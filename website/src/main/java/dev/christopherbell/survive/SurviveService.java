package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveSnapshot;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import dev.christopherbell.survive.model.SurviveResource;
import java.time.Clock;
import dev.christopherbell.survive.model.SurviveSavedWorld;
import dev.christopherbell.survive.persistence.SurviveWorldRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Owns the single world, bounded survivor credentials, expiry and atomic command processing. */
@Service
public class SurviveService {
  static final Duration IDLE_TIMEOUT = Duration.ofHours(2);
  private SurviveWorld world;
  private Long storageVersion;
  private final SurviveWorldRepository repository;
  private final Clock clock;
  private final IntSupplier rollPercent;
  private final int playerCapacity;

  @Autowired
  public SurviveService(SurviveWorldRepository repository) {
    this(Clock.systemUTC(), () -> ThreadLocalRandom.current().nextInt(1, 101), 1000, repository);
  }

  SurviveService(Clock clock, IntSupplier rollPercent, int playerCapacity, SurviveWorldRepository repository) {
    this.repository = repository;
    this.clock = clock;
    this.rollPercent = rollPercent;
    this.playerCapacity = playerCapacity;
  }

  /** Starts a survivor or replaces only the presented survivor, preserving the shared camp. */
  public synchronized JoinedSurvivor join(String previousToken, String requestedName) {
    return joinForOwner(previousToken, requestedName, null);
  }

  /** Resumes a live account survivor; only a terminal character can be replaced. */
  public synchronized JoinedSurvivor joinForOwner(String previousToken, String requestedName, String accountId) {
    loadWorld();
    String ownerKey = ownerKey(previousToken, accountId);
    var existing = world.players.get(ownerKey);
    if (accountId != null && existing != null && existing.status != Status.DEAD && existing.status != Status.ESCAPED) {
      existing.lastSeen = clock.instant();
      commitWorld();
      return new JoinedSurvivor(null, snapshotFor(existing));
    }
    String name = requestedName == null ? "" : requestedName.strip();
    if (name.isBlank() || name.length() > 32 || name.codePoints().anyMatch(Character::isISOControl)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a name of 1 to 32 characters without control characters.");
    }
    expireInactivePlayers();
    boolean replacingPlayer = world.players.containsKey(ownerKey);
    if (!replacingPlayer && world.players.size() >= playerCapacity) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The camp is full. Please try later.");
    }
    if (replacingPlayer) world.players.remove(ownerKey);
    String token = UUID.randomUUID().toString();
    var player = new SurvivePlayer(name, clock.instant(), accountId, UUID.randomUUID().toString());
    if (accountId != null && existing != null) player.revision = Math.addExact(existing.revision, 1);
    world.players.put(accountId == null ? token : ownerKey, player);
    world.recordEvent(name + " arrived at camp.");
    commitWorld();
    return new JoinedSurvivor(accountId == null ? token : null, snapshotFor(player));
  }

  /** Returns this survivor's immutable view of the current shared world without creating a player. */
  public synchronized Optional<SurviveSnapshot> find(String token) {
    return findForOwner(token, null);
  }

  /** Refreshes durable presence without deleting inactive saved characters. */
  public synchronized Optional<SurviveSnapshot> findForOwner(String token, String accountId) {
    loadWorld();
    expireInactivePlayers();
    var player = world.players.get(ownerKey(token, accountId));
    if (player == null) return Optional.empty();
    player.lastSeen = clock.instant();
    commitWorld();
    return Optional.of(snapshotFor(player));
  }

  /** Applies an allowed command once; stale requests must read fresh state rather than retry blindly. */
  public synchronized SurviveSnapshot act(String token, SurviveAction action, long expectedRevision) {
    return actForOwner(token, null, action, expectedRevision);
  }

  /** Authenticated account ownership takes precedence over every guest cookie. */
  public synchronized SurviveSnapshot actForOwner(String token, String accountId, SurviveAction action, long expectedRevision) {
    loadWorld();
    expireInactivePlayers();
    var player = world.players.get(ownerKey(token, accountId));
    if (player == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Your survivor expired. Join the camp again.");
    if (expectedRevision != player.revision) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Your survivor changed. Refresh the game before acting again.");
    }
    if (action == null || !world.actionsFor(player).contains(action)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That action is unavailable in the current state.");
    }
    player.lastSeen = clock.instant();
    world.perform(action, player, rollPercent);
    commitWorld();
    return snapshotFor(player);
  }

  private void expireInactivePlayers() {
    var idleDeadline = clock.instant().minus(IDLE_TIMEOUT);
    boolean removed = world.players.values().removeIf(player -> player.accountId == null && !player.lastSeen.isAfter(idleDeadline));
    if (removed) world.recordEvent("Inactive survivors left camp.");
  }

  /** Atomically gives supplies to a public recipient ID; private owner credentials select the sender. */
  public synchronized SurviveSnapshot giveSupplies(String token, String recipientId,
      SurviveResource resource, int quantity, long expectedRevision) {
    return giveSuppliesForOwner(token, null, recipientId, resource, quantity, expectedRevision);
  }

  /** Commits both sides of a gift in one durable world replacement. */
  public synchronized SurviveSnapshot giveSuppliesForOwner(String token, String accountId, String recipientId,
      SurviveResource resource, int quantity, long expectedRevision) {
    loadWorld();
    expireInactivePlayers();
    var sender = world.players.get(ownerKey(token, accountId));
    if (sender == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Your survivor expired. Join the camp again.");
    if (sender.revision != expectedRevision) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Your survivor changed. Refresh before giving supplies.");
    }
    if (resource == null || quantity < 1 || quantity > SurviveWorld.INVENTORY_CAPACITY) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give 1 to 10 wood or food.");
    }
    var recipient = world.players.values().stream()
        .filter(player -> player.survivorId.equals(recipientId) && player.lastSeen.isAfter(clock.instant().minus(IDLE_TIMEOUT))).findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That survivor left the camp. Refresh the world."));
    if (sender == recipient || sender.status != Status.EXPLORING || recipient.status != Status.EXPLORING) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both survivors must be at camp to give supplies to each other.");
    }
    int available = switch (resource) { case WOOD -> sender.wood; case FOOD -> sender.food; };
    if (available < quantity) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You do not have enough supplies to give.");
    if (recipient.wood + recipient.food + quantity > SurviveWorld.INVENTORY_CAPACITY) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That survivor's inventory has no room for this gift.");
    }
    sender.lastSeen = clock.instant();
    world.giveSupplies(sender, recipient, resource, quantity);
    commitWorld();
    return snapshotFor(sender);
  }

  private static String ownerKey(String token, String accountId) {
    // A cookie can never address the account namespace, even if its value is forged.
    if (accountId != null) return "account:" + accountId;
    if (token == null) return null;
    try { return UUID.fromString(token).toString().equals(token) ? token : null; }
    catch (IllegalArgumentException invalidToken) { return null; }
  }

  private void loadWorld() {
    var saved = repository.load().orElseGet(SurviveSavedWorld::empty);
    world = SurviveWorld.restore(saved);
    storageVersion = saved.version();
  }

  private void commitWorld() {
    try {
      storageVersion = repository.save(world.saveState(storageVersion)).version();
    } catch (OptimisticLockingFailureException changed) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "The world changed. Refresh before acting again.", changed);
    }
  }

  private SurviveSnapshot snapshotFor(SurvivePlayer player) {
    return world.snapshotFor(player, clock.instant().minus(IDLE_TIMEOUT));
  }

  /** Internal join result: the controller writes the credential only to a private cookie. */
  public record JoinedSurvivor(String token, SurviveSnapshot state) {}
}
