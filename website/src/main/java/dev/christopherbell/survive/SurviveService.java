package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveSnapshot;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import dev.christopherbell.survive.model.SurviveResource;
import java.time.Clock;
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
  private final SurviveWorld world = new SurviveWorld();
  private final Clock clock;
  private final IntSupplier rollPercent;
  private final int playerCapacity;

  public SurviveService() {
    this(Clock.systemUTC(), () -> ThreadLocalRandom.current().nextInt(1, 101), 1000);
  }

  SurviveService(Clock clock, IntSupplier rollPercent, int playerCapacity) {
    this.clock = clock;
    this.rollPercent = rollPercent;
    this.playerCapacity = playerCapacity;
  }

  /** Starts a survivor or replaces only the presented survivor, preserving the shared camp. */
  public synchronized JoinedSurvivor join(String previousToken, String requestedName) {
    String name = requestedName == null ? "" : requestedName.strip();
    if (name.isBlank() || name.length() > 32 || name.codePoints().anyMatch(Character::isISOControl)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a name of 1 to 32 characters without control characters.");
    }
    expireInactivePlayers();
    boolean replacingPlayer = world.players.containsKey(previousToken);
    if (!replacingPlayer && world.players.size() >= playerCapacity) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The camp is full. Please try later.");
    }
    if (replacingPlayer) world.players.remove(previousToken);
    String token = UUID.randomUUID().toString();
    var player = new SurvivePlayer(name, clock.instant());
    world.players.put(token, player);
    world.recordEvent(name + " arrived at camp.");
    return new JoinedSurvivor(token, world.snapshotFor(player));
  }

  /** Returns this survivor's immutable view of the current shared world without creating a player. */
  public synchronized Optional<SurviveSnapshot> find(String token) {
    expireInactivePlayers();
    var player = world.players.get(token);
    if (player == null) return Optional.empty();
    player.lastSeen = clock.instant();
    return Optional.of(world.snapshotFor(player));
  }

  /** Applies an allowed command once; stale requests must read fresh state rather than retry blindly. */
  public synchronized SurviveSnapshot act(String token, SurviveAction action, long expectedRevision) {
    expireInactivePlayers();
    var player = world.players.get(token);
    if (player == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Your survivor expired. Join the camp again.");
    if (expectedRevision != player.revision) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Your survivor changed. Refresh the game before acting again.");
    }
    if (action == null || !world.actionsFor(player).contains(action)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That action is unavailable in the current state.");
    }
    player.lastSeen = clock.instant();
    world.perform(action, player, rollPercent);
    return world.snapshotFor(player);
  }

  private void expireInactivePlayers() {
    var idleDeadline = clock.instant().minus(IDLE_TIMEOUT);
    boolean removed = world.players.values().removeIf(player -> !player.lastSeen.isAfter(idleDeadline));
    if (removed) world.recordEvent("Inactive survivors left camp.");
  }

  /** Atomically gives supplies to a public recipient ID; the private cookie alone selects the sender. */
  public synchronized SurviveSnapshot giveSupplies(String token, String recipientId,
      SurviveResource resource, int quantity, long expectedRevision) {
    expireInactivePlayers();
    var sender = world.players.get(token);
    if (sender == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Your survivor expired. Join the camp again.");
    if (sender.revision != expectedRevision) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Your survivor changed. Refresh before giving supplies.");
    }
    if (resource == null || quantity < 1 || quantity > SurviveWorld.INVENTORY_CAPACITY) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give 1 to 10 wood or food.");
    }
    var recipient = world.players.values().stream()
        .filter(player -> player.survivorId.equals(recipientId)).findFirst()
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
    return world.snapshotFor(sender);
  }

  /** Internal join result: the controller writes the credential only to a private cookie. */
  public record JoinedSurvivor(String token, SurviveSnapshot state) {}
}
