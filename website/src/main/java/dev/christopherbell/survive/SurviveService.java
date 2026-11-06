package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveEnemy;
import dev.christopherbell.survive.model.SurviveParty;
import dev.christopherbell.survive.model.SurviveSnapshot;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import dev.christopherbell.survive.model.SurviveResource;
import dev.christopherbell.survive.model.SurviveRequests;
import java.time.Clock;
import dev.christopherbell.survive.model.SurviveSavedWorld;
import dev.christopherbell.survive.persistence.SurviveWorldRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
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
  private final SurvivePresence presence = new SurvivePresence();
  private final SurviveWildlife wildlife = new SurviveWildlife();

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
    loadSettledWorld();
    String ownerKey = ownerKey(previousToken, accountId);
    var existing = world.players.get(ownerKey);
    if (accountId != null && existing != null && existing.status != Status.DEAD && existing.status != Status.ESCAPED) {
      world.observeActivity(existing, clock.instant());
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
    if (replacingPlayer) {
      presence.remove(world.players.get(ownerKey).survivorId);
      world.players.remove(ownerKey);
    }
    String token = UUID.randomUUID().toString();
    var player = new SurvivePlayer(name, clock.instant(), accountId, UUID.randomUUID().toString());
    if (accountId != null && existing != null) {
      player.revision = Math.addExact(existing.revision, 1);
    }
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
    loadSettledWorld();
    expireInactivePlayers();
    var player = world.players.get(ownerKey(token, accountId));
    if (player == null) {
      return Optional.empty();
    }
    world.observeActivity(player, clock.instant());
    commitWorld();
    return Optional.of(snapshotFor(player));
  }

  /** Applies an allowed command once; stale requests must read fresh state rather than retry blindly. */
  public synchronized SurviveSnapshot act(String token, SurviveAction action, long expectedRevision) {
    return actForOwner(token, null, action, expectedRevision);
  }

  /** Authenticated account ownership takes precedence over every guest cookie. */
  public synchronized SurviveSnapshot actForOwner(String token, String accountId, SurviveAction action, long expectedRevision) {
    return actForOwner(token, accountId, action, expectedRevision, null);
  }

  /** Engages a named roaming target only at the survivor's acknowledged location. */
  public synchronized SurviveSnapshot actForOwner(String token, String accountId, SurviveAction action,
      long expectedRevision, String targetId) {
    loadSettledWorld();
    expireInactivePlayers();
    var player = world.players.get(ownerKey(token, accountId));
    if (player == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Your survivor expired. Join the camp again.");
    }
    if (expectedRevision != player.revision) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Your survivor changed. Refresh the game before acting again.");
    }
    var now = clock.instant();
    boolean fieldEngage = targetId != null && action == SurviveAction.ENGAGE && player.status == Status.EXPLORING;
    boolean talkAtWaystation = action == SurviveAction.TALK_WAYSTATION && player.status == Status.EXPLORING;
    boolean equipping = action == SurviveAction.EQUIP_WEAPON;
    if (action == null || targetId != null && !fieldEngage && !equipping
        || !fieldEngage && !talkAtWaystation && !world.actionsFor(player, now).contains(action)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That action is unavailable in the current state.");
    }
    var selectedWeapon = equipping ? player.packedWeapons.stream().filter(weapon -> weapon.id().equals(targetId))
        .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "That weapon is no longer in your pack.")) : null;
    if (!presence.permits(player.survivorId, action, now)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Walk to the action's landmark first.");
    }
    var pose = presence.activePose(player.survivorId, now);
    if (action == SurviveAction.REEL_IN && (pose == null || now.isBefore(player.fishing.biteAt())
        || !now.isBefore(player.fishing.escapesAt()))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reel in at the fishing bank while the fish is biting.");
    }
    var existingFight = fieldEngage ? world.fieldFightAt(targetId) : null;
    String partyId = fieldEngage ? world.acceptedPartyId(player, now) : null;
    SurviveWildlife.Animal hunted = null;
    if (existingFight != null) {
      if (existingFight.health() == 0 || !existingFight.partyId().equals(partyId)
          || pose == null || SurviveWorld.fieldAnimal(existingFight, true).distanceFrom(pose) > SurviveWildlife.HUNT_RANGE_METRES) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That party fight is unavailable or out of reach.");
      }
    } else if (fieldEngage || action == SurviveAction.HUNT && presence.tracks(player.survivorId)) {
      releaseFinishedEncounters(now);
      var raider = world.raiderApproaching(now);
      hunted = fieldEngage ? wildlife.targetInReach(pose, now, targetId, raider, world.reservedFieldRoutes())
          : wildlife.nearest(pose, now, false, raider, world.reservedFieldRoutes());
      if (hunted == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldEngage
            ? "That target is no longer available within five metres."
            : "Move within five metres of a roaming animal to hunt it.");
      }
    }
    world.observeActivity(player, now);
    if (player.status != Status.DEAD) {
      if (existingFight != null) {
        world.joinPartyFight(player, existingFight, now);
      } else if (fieldEngage && partyId != null && hunted.kind() != SurviveEnemy.RAIDER) {
        world.startPartyFight(player, partyId, hunted, now, false);
      } else if (fieldEngage) {
        world.engageFieldTarget(player, hunted.kind(), now);
      } else {
        world.perform(action, player, now, rollPercent, pose != null, hunted == null ? null : hunted.kind(), selectedWeapon);
      }
    }
    commitWorld();
    if (hunted != null && player.status == Status.COMBAT && world.fieldFightFor(player) == null) {
      wildlife.claim(player.survivorId, hunted);
    }
    if (action == SurviveAction.FLEE && player.status != Status.DEAD) {
      presence.returnToCamp(player.survivorId);
    }
    return snapshotFor(player);
  }

  /** Acknowledges movement and commits a new hostile encounter only after verified proximity. */
  public synchronized SurvivePresence.View pulseForOwner(String token, String accountId,
      dev.christopherbell.survive.model.SurviveMovement movement) {
    restoreSettledWorld(repository.loadForMovement().orElseGet(SurviveSavedWorld::empty));
    var player = world.players.get(ownerKey(token, accountId));
    var now = clock.instant();
    if (player == null || !player.lastSeen.isAfter(now.minus(IDLE_TIMEOUT))) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Your survivor expired. Rejoin the camp.");
    }
    var currentFight = world.fieldFightFor(player);
    var view = presence.pulse(player.survivorId, player.name, player.status, movement, now,
        currentFight == null ? null : SurviveWorld.fieldAnimal(currentFight, false));
    boolean fishingInterrupted = view.accepted() && world.leaveFishingBank(player, view.self());
    releaseFinishedEncounters(now);
    if (view.accepted() && player.status == Status.EXPLORING) {
      var attacker = wildlife.nearest(view.self(), now, true, world.raiderApproaching(now), world.reservedFieldRoutes());
      if (attacker != null) {
        world.observeActivity(player, now);
        if (player.status == Status.EXPLORING) {
          var partyId = world.acceptedPartyId(player, now);
          if (partyId != null && attacker.kind() != SurviveEnemy.RAIDER) {
            world.startPartyFight(player, partyId, attacker, now, true);
          } else {
            world.aggro(player, attacker.kind(), now);
          }
        }
        commitWorld();
        fishingInterrupted = false;
        if (player.status == Status.COMBAT && world.fieldFightFor(player) == null) {
          wildlife.claim(player.survivorId, attacker);
        }
      }
    }
    if (fishingInterrupted) {
      commitWorld();
    }
    var claimedFight = world.fieldFightFor(player);
    var animals = new ArrayList<>(wildlife.roaming(now, world.raiderApproaching(now), world.reservedFieldRoutes()));
    animals.addAll(world.visibleFieldClaims(player, now));
    var encounter = claimedFight == null ? wildlife.encounter(player.survivorId) : SurviveWorld.fieldAnimal(claimedFight, false);
    return view.withScene(player.status, describeVisiblePlayers(view.others()), animals, encounter);
  }

  /** Resolve appearance from the settled world, even when the visible player has not sent another pulse. */
  private List<SurvivePresence.Pose> describeVisiblePlayers(List<SurvivePresence.Pose> visiblePlayers) {
    if (visiblePlayers.isEmpty()) {
      return visiblePlayers;
    }
    var bySurvivorId = world.players.values().stream()
        .collect(Collectors.toMap(player -> player.survivorId, player -> player));
    var describedPlayers = new ArrayList<SurvivePresence.Pose>(visiblePlayers.size());
    for (var pose : visiblePlayers) {
      var player = bySurvivorId.get(pose.survivorId());
      if (player == null) {
        continue;
      }
      double heading = pose.heading();
      boolean engaged = player.nextStrikeAt != null;
      if (engaged) {
        var fight = world.fieldFightFor(player);
        var target = fight == null ? wildlife.encounter(player.survivorId) : SurviveWorld.fieldAnimal(fight, false);
        if (target != null && (target.x() != pose.x() || target.z() != pose.z())) {
          heading = Math.atan2(target.x() - pose.x(), target.z() - pose.z());
        }
      }
      var appearance = new SurvivePresence.Appearance(player.weapon, engaged, player.defending);
      describedPlayers.add(new SurvivePresence.Pose(pose.survivorId(), pose.name(), pose.x(), pose.z(),
          heading, pose.sequence(), player.status, appearance));
    }
    return describedPlayers;
  }

  private void releaseFinishedEncounters(Instant now) {
    wildlife.releaseFinished(world.players.values().stream()
        .filter(player -> player.status == Status.COMBAT)
        .map(player -> player.survivorId).collect(Collectors.toSet()), now);
  }

  /** Releases only this owner's transient pose when the browser falls back to the text adventure. */
  public synchronized void leaveIslandForOwner(String token, String accountId) {
    loadSettledWorld();
    var player = world.players.get(ownerKey(token, accountId));
    if (player != null) {
      presence.remove(player.survivorId);
    }
  }

  private void expireInactivePlayers() {
    var idleDeadline = clock.instant().minus(IDLE_TIMEOUT);
    boolean removed = world.players.values().removeIf(player -> player.accountId == null && !player.lastSeen.isAfter(idleDeadline));
    if (removed) {
      world.recordEvent("Inactive survivors left camp.");
    }
  }

  /** Atomically gives supplies to a public recipient ID; private owner credentials select the sender. */
  public synchronized SurviveSnapshot giveSupplies(String token, String recipientId,
      SurviveResource resource, int quantity, long expectedRevision) {
    return giveSuppliesForOwner(token, null, recipientId, resource, quantity, expectedRevision);
  }

  /** Commits both sides of a gift in one durable world replacement. */
  public synchronized SurviveSnapshot giveSuppliesForOwner(String token, String accountId, String recipientId,
      SurviveResource resource, int quantity, long expectedRevision) {
    loadSettledWorld();
    expireInactivePlayers();
    var sender = world.players.get(ownerKey(token, accountId));
    if (sender == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Your survivor expired. Join the camp again.");
    }
    if (sender.revision != expectedRevision) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Your survivor changed. Refresh before giving supplies.");
    }
    if (resource == null || quantity < 1 || quantity > SurviveWorld.INVENTORY_CAPACITY) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give 1 to 10 wood, food or stone.");
    }
    var recipient = world.players.values().stream()
        .filter(player -> player.survivorId.equals(recipientId) && player.lastSeen.isAfter(clock.instant().minus(IDLE_TIMEOUT))).findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That survivor left the camp. Refresh the world."));
    if (sender == recipient || sender.status != Status.EXPLORING || recipient.status != Status.EXPLORING) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both survivors must be at camp to give supplies to each other.");
    }
    if (SurviveWorld.suppliesOf(sender, resource) < quantity) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You do not have enough supplies to give.");
    }
    if (recipient.inventoryCount() + quantity > SurviveWorld.INVENTORY_CAPACITY) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That survivor's inventory has no room for this gift.");
    }
    world.observeActivity(sender, clock.instant());
    if (sender.status != Status.DEAD) {
      world.giveSupplies(sender, recipient, resource, quantity);
    }
    commitWorld();
    return snapshotFor(sender);
  }

  /** Applies a party command using the private owner identity and the exact invitation or target ID. */
  public synchronized SurviveSnapshot changePartyForOwner(String token, String accountId, SurviveRequests.Party request) {
    loadSettledWorld();
    expireInactivePlayers();
    var survivor = world.players.get(ownerKey(token, accountId));
    if (survivor == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Your survivor expired. Join the camp again.");
    }
    if (request.command() == SurviveParty.Command.LEAVE && world.fieldFightFor(survivor) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Flee or finish the fight before leaving the party.");
    }
    world.parties.apply(survivor, request, world.players.values(), clock.instant());
    commitWorld();
    return snapshotFor(survivor);
  }

  private static String ownerKey(String token, String accountId) {
    // A cookie can never address the account namespace, even if its value is forged.
    if (accountId != null) {
      return "account:" + accountId;
    }
    if (token == null) {
      return null;
    }
    try { return UUID.fromString(token).toString().equals(token) ? token : null; }
    catch (IllegalArgumentException invalidToken) { return null; }
  }

  /**
   * Loads the durable world and applies any nightfalls and dawns that passed since it was saved.
   * Due combat rounds are committed here so even a stale command cannot discard elapsed attacks.
   */
  private void loadSettledWorld() {
    restoreSettledWorld(repository.load().orElseGet(SurviveSavedWorld::empty));
  }

  private void restoreSettledWorld(SurviveSavedWorld saved) {
    world = SurviveWorld.restore(saved);
    storageVersion = saved.version();
    var now = clock.instant();
    boolean worldAdvanced = world.settleUntil(now);
    boolean combatAdvanced = world.settleCombatUntil(now);
    boolean fishingAdvanced = world.settleFishingUntil(now);
    if (worldAdvanced || combatAdvanced || fishingAdvanced) {
      commitWorld();
    }
  }

  private void commitWorld() {
    try {
      storageVersion = repository.save(world.saveState(storageVersion)).version();
    } catch (OptimisticLockingFailureException changed) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "The world changed. Refresh before acting again.", changed);
    }
  }

  private SurviveSnapshot snapshotFor(SurvivePlayer player) {
    var now = clock.instant();
    return world.snapshotFor(player, now, now.minus(IDLE_TIMEOUT));
  }

  /** Internal join result: the controller writes the credential only to a private cookie. */
  public record JoinedSurvivor(String token, SurviveSnapshot state) {}
}
