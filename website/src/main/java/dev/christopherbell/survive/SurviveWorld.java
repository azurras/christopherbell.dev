package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveEnemy;
import dev.christopherbell.survive.model.SurviveFieldFight;
import dev.christopherbell.survive.model.SurviveParty;
import dev.christopherbell.survive.model.SurvivePackedWeapon;
import dev.christopherbell.survive.model.SurviveResource;
import dev.christopherbell.survive.model.SurviveSavedWorld;
import dev.christopherbell.survive.model.SurviveSnapshot;
import dev.christopherbell.survive.model.SurviveSnapshot.Phase;
import dev.christopherbell.survive.model.SurviveSnapshot.Recipient;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import dev.christopherbell.survive.model.SurviveStrike;
import dev.christopherbell.survive.model.SurviveWeapon;
import dev.christopherbell.survive.model.SurviveWaystation;
import dev.christopherbell.survive.model.SurviveWaystation.Assignment;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * One authoritative world: survivors own their supplies while camp structures and events are shared.
 * Adapted from azurras/survive (MIT); see README.md for source and completed mechanics.
 * The service serializes all access, allowing a future transport to reuse the same commands.
 * World time is settled lazily: nightfall raids and dawn wake-ups are applied when someone next
 * loads the world, in the order they happened.
 */
final class SurviveWorld {
  static final int INVENTORY_CAPACITY = 10;
  static final int BEDS_PER_SHELTER = 2;
  private static final int MAX_EVENTS = 30;
  private static final int MAX_STRUCTURES = 1000;
  private static final int MAX_NIGHTS_SETTLED_AT_ONCE = 3;
  private static final int NIGHTS_PER_RAID_WEEK = 7;
  private static final int RAID_INJURY_TO_SLEEPERS = 2;
  private static final Duration SLEEP_PER_HEALTH = Duration.ofMinutes(2);
  private static final Duration STRIKE_INTERVAL = Duration.ofSeconds(3);
  private static final int SKILL_COST = 100;
  private static final String HEAVY_STRIKE = "Heavy Strike";
  private static final String CRUSHING_BLOW = "Crushing Blow";
  private static final int IMPACT_BONUS = 3;
  private static final Duration IMPACT_MIN_DELAY = Duration.ofSeconds(1);
  private static final Duration IMPACT_WINDOW = Duration.ofSeconds(5);
  private static final int MAX_TACTICAL_POINTS = 300;
  private static final int MAX_ELAPSED_ROUNDS = 16;
  private static final int FULLNESS_INTERVAL_MILLISECONDS = 300_000;
  private static final Duration MAX_ACTIVE_GAP = Duration.ofSeconds(15);
  private static final Duration RAID_WINDOW = Duration.ofSeconds(60);
  final Map<String, SurvivePlayer> players = new LinkedHashMap<>();
  final SurviveParties parties = new SurviveParties();
  private final Map<String, SurviveFieldFight> fieldFights = new LinkedHashMap<>();
  private final ArrayDeque<String> events = new ArrayDeque<>();
  private int shelters;
  private int boats;
  private long revision;
  /** Nightfalls and dawns already applied; null until the first settlement starts the count. */
  private Long settledBoundaries;
  private Long activeRaidNight;

  void recordEvent(String event) {
    if (events.size() == MAX_EVENTS) {
      events.removeFirst();
    }
    events.addLast(event);
    revision++;
  }

  static SurviveWorld restore(SurviveSavedWorld saved) {
    var world = new SurviveWorld();
    world.shelters = saved.shelters();
    world.boats = saved.boats();
    world.revision = saved.revision();
    world.settledBoundaries = saved.settledBoundaries();
    world.activeRaidNight = saved.activeRaidNight();
    world.events.addAll(saved.events());
    world.parties.restore(saved.parties());
    for (var fight : saved.fieldFights()) {
      world.fieldFights.put(fight.routeId(), fight);
    }
    for (var stored : saved.players()) {
      var player = new SurvivePlayer(stored.name(), stored.lastSeen(), stored.accountId(), stored.survivorId());
      player.health = stored.health();
      player.strength = stored.strength();
      player.stamina = stored.stamina();
      player.experience = stored.experience();
      player.experienceToNextLevel = stored.experienceToNextLevel();
      player.wood = stored.wood();
      player.food = stored.food();
      player.stone = stored.stone();
      player.hunger = stored.hunger();
      player.activeFullnessMilliseconds = stored.activeFullnessMilliseconds();
      player.waystationAssignment = stored.waystationAssignment();
      player.enemy = stored.enemy();
      player.enemyHealth = stored.enemyHealth();
      player.struckFirst = stored.struckFirst();
      player.nextStrikeAt = stored.nextStrikeAt();
      player.lastStrike = stored.lastStrike();
      player.tacticalPoints = stored.tacticalPoints();
      player.defending = stored.defending();
      player.combatOutcome = stored.combatOutcome();
      player.weapon = stored.weapon();
      player.weaponDurability = stored.weaponDurability();
      player.packedWeapons.addAll(stored.packedWeapons());
      player.guarding = stored.guarding();
      player.sleptAt = stored.sleptAt();
      player.status = stored.status();
      player.revision = stored.revision();
      player.message = stored.message();
      world.players.put(stored.accountId() == null ? stored.token() : "account:" + stored.accountId(), player);
    }
    return world;
  }

  SurviveSavedWorld saveState(Long storageVersion) {
    var storedPlayers = players.entrySet().stream().map(entry -> {
      var player = entry.getValue();
      return new SurviveSavedWorld.Player(player.accountId, player.accountId == null ? entry.getKey() : null,
          player.survivorId, player.name, player.lastSeen, player.health, player.strength, player.stamina,
          player.experience, player.experienceToNextLevel, player.wood, player.food, player.enemyHealth,
          player.status, player.revision, player.message, player.stone, player.hunger, player.enemy,
          player.weapon, player.weaponDurability, player.guarding, player.sleptAt, player.struckFirst,
          player.nextStrikeAt, player.tacticalPoints, player.defending, player.combatOutcome,
          player.activeFullnessMilliseconds, player.waystationAssignment, player.packedWeapons, player.lastStrike);
    }).toList();
    return new SurviveSavedWorld("shared", storageVersion, revision, shelters, boats, List.copyOf(events),
        storedPlayers, settledBoundaries, activeRaidNight, parties.save(), List.copyOf(fieldFights.values()));
  }

  SurviveSnapshot snapshotFor(SurvivePlayer player, Instant now, Instant activeDeadline) {
    return new SurviveSnapshot(player.name, player.health, player.strength, player.stamina,
        player.experience, player.experienceToNextLevel, player.wood, player.food,
        INVENTORY_CAPACITY, player.enemyHealth, player.status, player.revision, revision,
        shelters, boats, players.values().stream().filter(survivor -> survivor.lastSeen.isAfter(activeDeadline)).map(survivor -> survivor.name).toList(),
        List.copyOf(events), actionsFor(player, now), player.message, player.survivorId,
        player.status == Status.EXPLORING ? players.values().stream()
            .filter(recipient -> recipient != player && recipient.status == Status.EXPLORING && recipient.lastSeen.isAfter(activeDeadline))
            .map(recipient -> new Recipient(recipient.survivorId, recipient.name)).toList() : List.of(), player.accountId != null,
        SurviveClock.phaseAt(now), SurviveClock.dayAt(now), SurviveClock.secondsToNextPhaseAt(now),
        player.hunger, player.stone, player.weapon, player.weaponDurability, player.enemy,
        bedsInUse(), shelters * BEDS_PER_SHELTER, player.guarding,
        player.nextStrikeAt, player.nextStrikeAt == null ? 0 : Math.max(0, Duration.between(now, player.nextStrikeAt).toMillis()),
        player.tacticalPoints, player.defending, player.combatOutcome, parties.viewFor(player, players.values(), now),
        weaponSkillFor(player.weapon), SurviveWaystation.KEEPER, player.waystationAssignment,
        player.packedWeapons.stream().map(SurviveSnapshot.WeaponChoice::from).toList());
  }

  /** Settles elapsed fights even when their participants did not send any requests. */
  boolean settleCombatUntil(Instant now) {
    boolean changed = settleFieldCombat(now);
    for (var player : players.values()) {
      if (fieldFightFor(player) != null) {
        continue;
      }
      for (int round = 0; round < MAX_ELAPSED_ROUNDS && player.nextStrikeAt != null
          && !player.nextStrikeAt.isAfter(now); round++) {
        var strikeAt = player.nextStrikeAt;
        player.nextStrikeAt = strikeAt.plus(STRIKE_INTERVAL);
        player.tacticalPoints = Math.min(MAX_TACTICAL_POINTS, player.tacticalPoints + 50);
        String outcome = attack(player, (player.attackDamage() + 1) / 2, null, strikeAt);
        recordAction(player, outcome, true);
        changed = true;
      }
    }
    return changed;
  }

  /**
   * Applies every nightfall raid and dawn since the last settlement, oldest first. The first
   * settlement only starts the count, so deploying the clock never replays the past. After a long
   * quiet spell only the latest nights are applied.
   */
  boolean settleUntil(Instant now) {
    long previousRevision = revision;
    long boundariesPassed = SurviveClock.boundariesPassedBy(now);
    if (settledBoundaries == null) {
      settledBoundaries = boundariesPassed;
      return false;
    }
    long firstBoundaryToApply = Math.max(settledBoundaries, boundariesPassed - 2L * MAX_NIGHTS_SETTLED_AT_ONCE);
    if (firstBoundaryToApply > settledBoundaries) {
      recordEvent("Several nights passed quietly while the camp slept.");
    }
    for (long boundary = firstBoundaryToApply; boundary < boundariesPassed; boundary++) {
      settleRaidUntil(SurviveClock.boundaryInstant(boundary));
      long night = SurviveClock.nightOf(boundary);
      if (SurviveClock.isNightfall(boundary)) {
        activeRaidNight = night;
        recordEvent("Night " + (night + 1) + ": a raider approaches camp. Intercept the raider within one minute.");
      }
      else {
        wakeEveryoneAtDawn(night);
      }
    }
    settledBoundaries = Math.max(settledBoundaries, boundariesPassed);
    settleRaidUntil(now);
    return previousRevision != revision;
  }

  /** Projects the active raid's approach for both the world view and proximity checks. */
  SurviveWildlife.Animal raiderApproaching(Instant now) {
    if (activeRaidNight == null || players.values().stream().anyMatch(player -> player.enemy == SurviveEnemy.RAIDER)) {
      return null;
    }
    double seconds = Duration.between(SurviveClock.boundaryInstant(activeRaidNight * 2), now).toMillis() / 1000.0;
    double progress = Math.clamp(seconds / 30, 0, 1);
    return new SurviveWildlife.Animal("camp-raider", SurviveEnemy.RAIDER, -22 + 19 * progress, -16 + 14 * progress,
        Math.atan2(19, 14), 7);
  }

  private void settleRaidUntil(Instant now) {
    if (activeRaidNight == null) {
      return;
    }
    var deadline = SurviveClock.boundaryInstant(activeRaidNight * 2).plus(RAID_WINDOW);
    if (now.isBefore(deadline)) {
      return;
    }
    settleCombatUntil(deadline);
    if (activeRaidNight != null) {
      long night = activeRaidNight;
      activeRaidNight = null;
      for (var player : players.values()) {
        if (player.enemy == SurviveEnemy.RAIDER) {
          player.stopFighting();
          recordAction(player, "The raider broke away to raid the camp.", true);
        }
      }
      raidAtNightfall(night);
    }
  }

  /** Applies a previously validated gift while the service holds the world's monitor. */
  void giveSupplies(SurvivePlayer sender, SurvivePlayer recipient, SurviveResource resource, int quantity) {
    addSupplies(sender, resource, -quantity);
    addSupplies(recipient, resource, quantity);
    String supplies = quantity + " " + resource.name().toLowerCase(Locale.ROOT);
    sender.message = "You gave " + supplies + " to " + recipient.name + ".";
    recipient.message = sender.name + " gave you " + supplies + ".";
    sender.revision++;
    recipient.revision++;
    recordEvent(sender.name + " gave " + supplies + " to " + recipient.name + ".");
  }

  /** Supplies a survivor holds of one resource. */
  static int suppliesOf(SurvivePlayer player, SurviveResource resource) {
    return switch (resource) {
      case WOOD -> player.wood;
      case FOOD -> player.food;
      case STONE -> player.stone;
    };
  }

  void perform(SurviveAction action, SurvivePlayer player, Instant now, IntSupplier rollPercent) {
    perform(action, player, now, rollPercent, false, null, null);
  }

  void perform(SurviveAction action, SurvivePlayer player, Instant now, IntSupplier rollPercent,
      boolean onIsland, SurviveEnemy huntedAnimal, SurvivePackedWeapon selectedWeapon) {
    var phase = SurviveClock.phaseAt(now);
    boolean wasFighting = player.status == Status.COMBAT;
    String outcome = switch (action) {
      case GATHER -> ventureOut(player, phase, rollPercent, onIsland, () -> gatherWood(player, rollPercent));
      case GATHER_STONE -> ventureOut(player, phase, rollPercent, onIsland,
          () -> gather(player, rollPercent, 40, SurviveResource.STONE));
      case HUNT -> ventureOut(player, phase, rollPercent, onIsland,
          () -> huntedAnimal == null ? hunt(player, phase, rollPercent) : revealAnimal(player, huntedAnimal));
      case FISH -> fish(player, rollPercent);
      case BUILD_SHELTER -> buildShelter(player);
      case BUILD_BOAT -> buildBoat(player);
      case CRAFT_CLUB -> craft(player, SurviveWeapon.CLUB);
      case CRAFT_AXE -> craft(player, SurviveWeapon.STONE_AXE);
      case CRAFT_SPEAR -> craft(player, SurviveWeapon.SPEAR);
      case ATTACK -> attack(player, player.attackDamage(), null, now);
      case ENGAGE -> engage(player, now);
      case DISENGAGE -> disengage(player);
      case WEAPON_SKILL -> useWeaponSkill(player, now);
      case DEFEND -> defend(player);
      case FLEE -> flee(player);
      case EAT -> eat(player);
      case REST -> rest(player);
      case GUARD -> guard(player);
      case SLEEP -> sleep(player, now);
      case WAKE -> wakeEarly(player, now);
      case ESCAPE -> escape(player);
      case TALK_WAYSTATION -> talkAtWaystation(player);
      case EQUIP_WEAPON -> equipWeapon(player, selectedWeapon);
      case STOW_WEAPON -> stowWeapon(player);
    };
    if (action == SurviveAction.FLEE) {
      removeFromFieldFight(player);
    }
    recordAction(player, outcome, wasFighting);
  }

  private void recordAction(SurvivePlayer player, String outcome, boolean wasFighting) {
    if (player.status == Status.DEAD) {
      outcome += " You have died. Start a new survivor to try again.";
    }
    if (wasFighting && player.status != Status.COMBAT) {
      player.combatOutcome = outcome;
    }
    player.message = outcome;
    player.revision++;
    recordEvent(player.name + ": " + outcome);
  }

  List<SurviveAction> actionsFor(SurvivePlayer player, Instant now) {
    return switch (player.status) {
      case DEAD, ESCAPED -> List.of();
      case SLEEPING -> List.of(SurviveAction.WAKE);
      case COMBAT -> combatActionsFor(player);
      case EXPLORING -> campActionsFor(player, SurviveClock.phaseAt(now));
    };
  }

  private static List<SurviveAction> combatActionsFor(SurvivePlayer player) {
    if (player.nextStrikeAt == null) {
      return List.of(SurviveAction.ENGAGE, SurviveAction.ATTACK, SurviveAction.DEFEND, SurviveAction.FLEE);
    }
    var actions = new ArrayList<>(List.of(SurviveAction.DISENGAGE, SurviveAction.FLEE));
    if (!player.defending) {
      actions.add(SurviveAction.DEFEND);
    }
    if (player.tacticalPoints >= SKILL_COST) {
      actions.add(SurviveAction.WEAPON_SKILL);
    }
    return List.copyOf(actions);
  }

  private List<SurviveAction> campActionsFor(SurvivePlayer player, Phase phase) {
    var actions = new ArrayList<>(List.of(SurviveAction.GATHER, SurviveAction.GATHER_STONE, SurviveAction.HUNT, SurviveAction.FISH));
    if (player.weapon != null) {
      actions.add(SurviveAction.STOW_WEAPON);
    }
    if (!player.packedWeapons.isEmpty()) {
      actions.add(SurviveAction.EQUIP_WEAPON);
    }
    if (player.wood >= 5 && shelters < MAX_STRUCTURES) {
      actions.add(SurviveAction.BUILD_SHELTER);
    }
    if (player.wood >= 10 && boats < MAX_STRUCTURES) {
      actions.add(SurviveAction.BUILD_BOAT);
    }
    for (var weapon : SurviveWeapon.values()) {
      if (player.wood >= weapon.woodCost() && player.stone >= weapon.stoneCost()) {
        actions.add(craftActionFor(weapon));
      }
    }
    if (player.food > 0 && (player.hunger < SurvivePlayer.MAX_HUNGER || player.health < SurvivePlayer.MAX_HEALTH)) {
      actions.add(SurviveAction.EAT);
    }
    if (shelters > 0 && player.health < SurvivePlayer.MAX_HEALTH) {
      actions.add(SurviveAction.REST);
    }
    if (phase == Phase.DUSK && !player.guarding) {
      actions.add(SurviveAction.GUARD);
    }
    if (phase != Phase.DAY && bedsInUse() < shelters * BEDS_PER_SHELTER) {
      actions.add(SurviveAction.SLEEP);
    }
    if (boats > 0) {
      actions.add(SurviveAction.ESCAPE);
    }
    return List.copyOf(actions);
  }

  private static SurviveAction craftActionFor(SurviveWeapon weapon) {
    return switch (weapon) {
      case CLUB -> SurviveAction.CRAFT_CLUB;
      case STONE_AXE -> SurviveAction.CRAFT_AXE;
      case SPEAR -> SurviveAction.CRAFT_SPEAR;
    };
  }

  /** Accrues connected, awake play time once, using the same durable timestamp as presence. */
  void observeActivity(SurvivePlayer player, Instant now) {
    if (!now.isAfter(player.lastSeen)) {
      return;
    }
    var elapsed = Duration.between(player.lastSeen, now);
    player.lastSeen = now;
    boolean awake = player.status == Status.EXPLORING || player.status == Status.COMBAT;
    if (!awake || elapsed.compareTo(MAX_ACTIVE_GAP) > 0) {
      return;
    }
    player.activeFullnessMilliseconds += (int) elapsed.toMillis();
    if (player.activeFullnessMilliseconds < FULLNESS_INTERVAL_MILLISECONDS) {
      return;
    }
    player.activeFullnessMilliseconds -= FULLNESS_INTERVAL_MILLISECONDS;
    if (player.hunger > 0) {
      player.hunger--;
      recordAction(player, "Fullness fell to " + player.hunger + "/" + SurvivePlayer.MAX_HUNGER + ".", false);
      return;
    }
    boolean wasFighting = player.status == Status.COMBAT;
    player.takeDamage(1);
    recordAction(player, "You are starving and lost 1 health.", wasFighting);
  }

  /** Leaving camp ends guard duty and risks an ambush that replaces the intended action. */
  private String ventureOut(SurvivePlayer player, Phase phase, IntSupplier rollPercent, boolean onIsland,
                            Supplier<String> intendedAction) {
    player.guarding = false;
    if (onIsland) {
      return intendedAction.get();
    }
    int ambushChancePercent = switch (phase) {
      case DAY -> 5;
      case DUSK -> 12;
      case NIGHT -> 25;
    };
    if (rollPercent.getAsInt() <= 100 - ambushChancePercent) {
      return intendedAction.get();
    }
    var ambusher = switch (phase) {
      case DAY -> SurviveEnemy.HOG;
      case DUSK -> SurviveEnemy.WOLF;
      case NIGHT -> rollPercent.getAsInt() <= 80 ? SurviveEnemy.WOLF : SurviveEnemy.BEAR;
    };
    player.startFighting(ambusher);
    return "A " + ambusher.displayName() + " ambushes you! It has " + ambusher.health()
        + " health. Select it to check or engage, or flee to camp.";
  }

  private String gatherWood(SurvivePlayer player, IntSupplier rollPercent) {
    boolean holdsAxe = player.weapon != null && player.weapon.improvesWoodGathering();
    return gather(player, rollPercent, holdsAxe ? 70 : 50, SurviveResource.WOOD);
  }

  private String gather(SurvivePlayer player, IntSupplier rollPercent, int successPercent, SurviveResource resource) {
    if (!player.hasInventorySpace()) {
      return "Your inventory is full. Craft or eat to make room.";
    }
    if (rollPercent.getAsInt() > successPercent) {
      return switch (resource) {
        case WOOD -> "The tree resists your efforts. No wood gathered.";
        case STONE, FOOD -> "You found no loose stone.";
      };
    }
    addSupplies(player, resource, 1);
    int previousStrength = player.strength;
    player.gainExperience();
    String gathered = switch (resource) {
      case WOOD -> "You gathered one piece of wood.";
      case STONE, FOOD -> "You gathered one stone.";
    };
    return gathered + (player.strength > previousStrength ? " Your strength and stamina increased!" : "");
  }

  private static void addSupplies(SurvivePlayer player, SurviveResource resource, int quantity) {
    switch (resource) {
      case WOOD -> player.wood += quantity;
      case FOOD -> player.food += quantity;
      case STONE -> player.stone += quantity;
    }
  }

  private String buildShelter(SurvivePlayer player) {
    player.wood -= 5;
    shelters++;
    return "built a shelter for the camp. It holds two sleepers, and every survivor can rest here.";
  }

  private String buildBoat(SurvivePlayer player) {
    player.wood -= 10;
    boats++;
    return "built a boat for the camp. One survivor can use it to escape.";
  }

  private static String craft(SurvivePlayer player, SurviveWeapon weapon) {
    player.wood -= weapon.woodCost();
    player.stone -= weapon.stoneCost();
    boolean mainSlotEmpty = player.weapon == null;
    if (mainSlotEmpty) {
      player.weapon = weapon;
      player.weaponDurability = weapon.durability();
    } else {
      player.packedWeapons.add(SurvivePackedWeapon.pack(weapon, weapon.durability()));
    }
    return "You crafted a " + weapon.displayName() + ": +" + weapon.damageBonus() + " damage for "
        + weapon.durability() + " attacks." + (mainSlotEmpty ? " Equipped in Main." : " Stored in your pack.");
  }

  private static String equipWeapon(SurvivePlayer player, SurvivePackedWeapon selectedWeapon) {
    player.packedWeapons.remove(selectedWeapon);
    if (player.weapon != null) {
      player.packedWeapons.add(SurvivePackedWeapon.pack(player.weapon, player.weaponDurability));
    }
    player.weapon = selectedWeapon.weapon();
    player.weaponDurability = selectedWeapon.durability();
    return "You equipped the " + player.weapon.displayName() + " in Main.";
  }

  private static String stowWeapon(SurvivePlayer player) {
    if (!player.hasInventorySpace()) {
      return "Your pack is full. Free one slot before removing your weapon.";
    }
    var weapon = player.weapon;
    player.packedWeapons.add(SurvivePackedWeapon.pack(weapon, player.weaponDurability));
    player.weapon = null;
    player.weaponDurability = 0;
    return "You stowed the " + weapon.displayName() + " in your pack.";
  }

  private String hunt(SurvivePlayer player, Phase phase, IntSupplier rollPercent) {
    if (rollPercent.getAsInt() < 40) {
      return "No animal found. You return to camp.";
    }
    var animal = switch (phase) {
      case DAY -> SurviveEnemy.HOG;
      case DUSK -> rollPercent.getAsInt() <= 50 ? SurviveEnemy.HOG : SurviveEnemy.WOLF;
      case NIGHT -> nightAnimalFor(rollPercent.getAsInt());
    };
    return revealAnimal(player, animal);
  }

  private static String revealAnimal(SurvivePlayer player, SurviveEnemy animal) {
    player.startFighting(animal);
    return "A wild " + animal.displayName() + " appears! It has " + animal.health()
        + " health. Select it to check or engage, or flee to camp.";
  }

  private static String fish(SurvivePlayer player, IntSupplier rollPercent) {
    player.guarding = false;
    if (!player.hasInventorySpace()) {
      return "Your inventory is full. Make room before fishing.";
    }
    if (rollPercent.getAsInt() > 60) {
      return "You cast your line, but the fish got away.";
    }
    player.food++;
    return "You reel in a fish. You gained 1 food.";
  }

  void aggro(SurvivePlayer player, SurviveEnemy animal, Instant now) {
    player.startFighting(animal);
    engage(player, now);
    recordAction(player, "The " + animal.displayName() + " spots you and attacks!", false);
  }

  void engageFieldTarget(SurvivePlayer player, SurviveEnemy target, Instant now) {
    player.startFighting(target);
    recordAction(player, engage(player, now), false);
  }

  String acceptedPartyId(SurvivePlayer player, Instant now) {
    var party = parties.viewFor(player, players.values(), now);
    return party != null && party.state() == SurviveParty.State.MEMBER ? party.id().toString() : null;
  }

  Set<String> reservedFieldRoutes() {
    return fieldFights.keySet();
  }

  SurviveFieldFight fieldFightAt(String routeId) {
    return fieldFights.get(routeId);
  }

  SurviveFieldFight fieldFightFor(SurvivePlayer player) {
    for (var fight : fieldFights.values()) {
      if (fight.fighters().contains(player.survivorId)) {
        return fight;
      }
    }
    return null;
  }

  static SurviveWildlife.Animal fieldAnimal(SurviveFieldFight fight, boolean joinable) {
    return new SurviveWildlife.Animal(fight.routeId(), fight.enemy(), fight.x(), fight.z(), fight.heading(),
        0, true, joinable);
  }

  List<SurviveWildlife.Animal> visibleFieldClaims(SurvivePlayer player, Instant now) {
    if (fieldFights.isEmpty()) {
      return List.of();
    }
    var partyId = acceptedPartyId(player, now);
    return fieldFights.values().stream().filter(fight -> fight.health() > 0)
        .filter(fight -> !fight.fighters().contains(player.survivorId))
        .map(fight -> fieldAnimal(fight, fight.partyId().equals(partyId))).toList();
  }

  void startPartyFight(SurvivePlayer player, String partyId, SurviveWildlife.Animal animal, Instant now, boolean aggro) {
    var fight = new SurviveFieldFight(animal.id(), UUID.randomUUID().toString(), partyId, animal.kind(),
        animal.x(), animal.z(), animal.heading(), animal.kind().health(), List.of(player.survivorId), null, null);
    fieldFights.put(animal.id(), fight);
    if (aggro) {
      aggro(player, animal.kind(), now);
    } else {
      engageFieldTarget(player, animal.kind(), now);
    }
  }

  void joinPartyFight(SurvivePlayer player, SurviveFieldFight fight, Instant now) {
    var fighters = new ArrayList<>(fight.fighters());
    fighters.add(player.survivorId);
    fieldFights.put(fight.routeId(), fight.withProgress(fight.health(), fighters, null));
    player.startFighting(fight.enemy());
    player.enemyHealth = fight.health();
    engage(player, now);
    recordAction(player, "You join the party's fight against the " + fight.enemy().displayName() + ".", false);
  }

  private List<SurvivePlayer> livingFighters(SurviveFieldFight fight) {
    return players.values().stream().filter(player -> fight.fighters().contains(player.survivorId)
        && player.status == Status.COMBAT).toList();
  }

  private boolean settleFieldCombat(Instant now) {
    boolean changed = false;
    for (var savedFight : List.copyOf(fieldFights.values())) {
      if (savedFight.respawnAt() != null) {
        if (!now.isBefore(savedFight.respawnAt())) {
          fieldFights.remove(savedFight.routeId());
          changed = true;
        }
        continue;
      }
      changed |= retainLivingFighters(savedFight);
      for (int round = 0; round < 2 * MAX_ELAPSED_ROUNDS; round++) {
        var fight = fieldFights.get(savedFight.routeId());
        if (fight == null || fight.health() == 0) {
          break;
        }
        var attacker = livingFighters(fight).stream()
            .filter(player -> player.nextStrikeAt != null && !player.nextStrikeAt.isAfter(now))
            .min(Comparator.comparing((SurvivePlayer player) -> player.nextStrikeAt)
                .thenComparingInt(player -> fight.fighters().indexOf(player.survivorId))).orElse(null);
        if (attacker == null) {
          break;
        }
        var strikeAt = attacker.nextStrikeAt;
        attacker.nextStrikeAt = strikeAt.plus(STRIKE_INTERVAL);
        attacker.tacticalPoints = Math.min(MAX_TACTICAL_POINTS, attacker.tacticalPoints + 50);
        recordAction(attacker, strikeFieldEnemy(fight, attacker, (attacker.attackDamage() + 1) / 2, null, strikeAt), true);
        changed = true;
      }
    }
    return changed;
  }

  private void removeFromFieldFight(SurvivePlayer player) {
    var fight = fieldFightFor(player);
    if (fight != null) {
      retainLivingFighters(fight);
    }
  }

  private boolean retainLivingFighters(SurviveFieldFight fight) {
    var living = livingFighters(fight).stream().map(player -> player.survivorId).toList();
    if (living.isEmpty()) {
      fieldFights.remove(fight.routeId());
    } else if (living.size() != fight.fighters().size() || fieldFights.get(fight.routeId()).health() != fight.health()) {
      fieldFights.put(fight.routeId(), fight.withProgress(fight.health(), living, null));
    }
    return living.size() != fight.fighters().size();
  }

  private String strikeFieldEnemy(SurviveFieldFight fight, SurvivePlayer attacker, int damage, String skill, Instant now) {
    attacker.lastStrike = new SurviveStrike(attacker.revision + 1, now, attacker.weapon, skill != null);
    int health = Math.max(0, fight.health() - damage);
    if (health > 0 && closesImpact(fight.skillOpening(), attacker, skill, now)) {
      health = Math.max(0, health - IMPACT_BONUS);
      recordEvent(attacker.name + " closed the Impact skillchain with Crushing Blow for " + IMPACT_BONUS + " bonus damage.");
    }
    var weapon = attacker.weapon;
    boolean blocked = attacker.defending || !attacker.struckFirst && weapon != null && weapon.keepsFirstBiteAway();
    attacker.defending = false;
    attacker.struckFirst = true;
    String weaponNote = attacker.wearWeaponAndReportBreak() ? " Your " + weapon.displayName() + " broke." : "";
    String strike = (skill == null ? "You dealt " : skill + " dealt ") + damage + " damage.";
    if (health == 0) {
      return finishPartyFight(fight, attacker, now, strike + weaponNote);
    }

    int bite = blocked ? 0 : fight.enemy().bite();
    attacker.takeDamage(bite);
    for (var fighter : livingFighters(fight)) {
      fighter.enemyHealth = health;
    }
    retainLivingFighters(fight.withProgress(health, fight.fighters(), null));
    if (skill != null) {
      updateSkillOpening(fight.routeId(), attacker, skill, now);
    }
    return strike + (blocked ? " You blocked the bite." : " The " + fight.enemy().displayName()
        + " bit you for " + bite + " damage.") + weaponNote;
  }

  private static boolean closesImpact(SurviveFieldFight.SkillOpening opening, SurvivePlayer attacker, String skill,
      Instant now) {
    return CRUSHING_BLOW.equals(skill) && opening != null && !opening.survivorId().equals(attacker.survivorId)
        && !now.isBefore(opening.openedAt().plus(IMPACT_MIN_DELAY)) && now.isBefore(opening.openedAt().plus(IMPACT_WINDOW));
  }

  private void updateSkillOpening(String routeId, SurvivePlayer attacker, String skill, Instant now) {
    var fight = fieldFights.get(routeId);
    if (fight == null) {
      return;
    }
    SurviveFieldFight.SkillOpening opening = null;
    if (HEAVY_STRIKE.equals(skill) && fight.fighters().contains(attacker.survivorId)) {
      opening = new SurviveFieldFight.SkillOpening(attacker.survivorId, now);
      recordEvent(attacker.name + " opened Impact with Heavy Strike. Follow with Crushing Blow in 1 to 5 seconds.");
    }
    fieldFights.put(routeId, fight.withSkillOpening(opening));
  }

  private String finishPartyFight(SurviveFieldFight fight, SurvivePlayer attacker, Instant now, String strike) {
    fieldFights.put(fight.routeId(), fight.withProgress(0, List.of(), now.plusSeconds(SurviveWildlife.RESPAWN_SECONDS)));
    recordEvent("The party defeated the " + fight.enemy().displayName() + ".");
    int totalFood = fight.enemy().foodReward();
    int attackerFood = awardFieldFood(attacker, totalFood);
    totalFood -= attackerFood;
    for (var fighter : livingFighters(fight)) {
      if (fighter == attacker) {
        continue;
      }
      int food = fighter.struckFirst ? awardFieldFood(fighter, totalFood) : 0;
      totalFood -= food;
      String patrolCredit = creditWaystationPatrol(fighter);
      fighter.stopFighting();
      recordAction(fighter, "Your party won the fight." + foodNotice(food) + patrolCredit, true);
    }
    String patrolCredit = creditWaystationPatrol(attacker);
    attacker.stopFighting();
    return strike + " Your party won the fight." + foodNotice(attackerFood) + patrolCredit;
  }

  private static String creditWaystationPatrol(SurvivePlayer survivor) {
    if (!survivor.struckFirst || survivor.waystationAssignment != Assignment.ACTIVE) {
      return "";
    }
    survivor.waystationAssignment = Assignment.RETURN;
    return " North Gate Patrol cleared. Return to the Waystation Keeper.";
  }

  private static String talkAtWaystation(SurvivePlayer survivor) {
    return switch (survivor.waystationAssignment) {
      case AVAILABLE -> {
        survivor.waystationAssignment = Assignment.ACTIVE;
        yield "Assignment accepted: North Gate Patrol. Fight a roaming creature alongside your party, then report back.";
      }
      case ACTIVE -> "North Gate Patrol: Land a hit and survive a victory in a shared party fight.";
      case RETURN -> {
        if (INVENTORY_CAPACITY - survivor.inventoryCount() < 2) {
          yield "North Gate Patrol: Free two inventory slots to claim your reward.";
        }
        survivor.food += 2;
        survivor.gainExperience();
        survivor.waystationAssignment = Assignment.COMPLETE;
        yield "Assignment complete: North Gate Patrol. You gained 2 food and patrol experience.";
      }
      case COMPLETE -> "Waystation Keeper: Thank you for completing North Gate Patrol.";
    };
  }

  private static int awardFieldFood(SurvivePlayer player, int availableFood) {
    int food = Math.min(availableFood, INVENTORY_CAPACITY - player.inventoryCount());
    player.food += food;
    return food;
  }

  private static String foodNotice(int food) {
    return food == 0 ? "" : " You gained " + food + " food.";
  }

  private static SurviveEnemy nightAnimalFor(int rollPercent) {
    if (rollPercent <= 50) {
      return SurviveEnemy.HOG;
    }
    return rollPercent <= 85 ? SurviveEnemy.WOLF : SurviveEnemy.BEAR;
  }

  private static String engage(SurvivePlayer player, Instant now) {
    player.nextStrikeAt = now.plus(STRIKE_INTERVAL);
    player.combatOutcome = null;
    return "You engage the " + player.enemy.displayName() + ". Attacks continue while you are away.";
  }

  private static String disengage(SurvivePlayer player) {
    player.disengage();
    return "You disengage. Automatic attacks have stopped.";
  }

  private static String defend(SurvivePlayer player) {
    String attack = player.enemy == SurviveEnemy.RAIDER ? "strike" : "bite";
    if (player.nextStrikeAt == null) {
      return "You brace against the " + player.enemy.displayName() + " and block the " + attack + ". Attack or flee next.";
    }
    player.defending = true;
    return "You brace to block the next " + attack + ".";
  }

  private String useWeaponSkill(SurvivePlayer player, Instant now) {
    String skill = weaponSkillFor(player.weapon);
    player.tacticalPoints -= SKILL_COST;
    player.nextStrikeAt = now.plus(STRIKE_INTERVAL);
    return attack(player, player.attackDamage() + 2, skill, now);
  }

  private static String weaponSkillFor(SurviveWeapon weapon) {
    return weapon == null ? HEAVY_STRIKE : switch (weapon) {
      case CLUB -> CRUSHING_BLOW;
      case STONE_AXE -> "Cleave";
      case SPEAR -> "Piercing Thrust";
    };
  }

  private String attack(SurvivePlayer player, int damage, String skill, Instant now) {
    var fieldFight = fieldFightFor(player);
    if (fieldFight != null) {
      return strikeFieldEnemy(fieldFight, player, damage, skill, now);
    }
    player.lastStrike = new SurviveStrike(player.revision + 1, now, player.weapon, skill != null);
    var enemy = player.enemy;
    boolean reachSparesBite = !player.struckFirst && player.weapon != null && player.weapon.keepsFirstBiteAway();
    boolean defended = player.defending;
    player.defending = false;
    var heldWeapon = player.weapon;
    player.struckFirst = true;
    player.enemyHealth = Math.max(0, player.enemyHealth - damage);
    if (enemy == SurviveEnemy.RAIDER && player.enemyHealth == 0) {
      activeRaidNight = null;
      recordEvent(player.name + " repelled the raid.");
    }
    String weaponNote = player.wearWeaponAndReportBreak() ? " Your " + heldWeapon.displayName() + " broke." : "";
    // Preserve the original simultaneous exchange, including the finishing blow's bite.
    int bite = reachSparesBite || defended ? 0 : enemy.bite();
    player.takeDamage(bite);
    String attack = enemy == SurviveEnemy.RAIDER ? "strike" : "bite";
    String hitVerb = enemy == SurviveEnemy.RAIDER ? "struck" : "bit";
    if (player.status == Status.DEAD) {
      return "You attacked the " + enemy.displayName() + ", but the " + attack + " was fatal." + weaponNote;
    }
    String biteNote = defended ? " Your guard blocked the " + attack + "." : bite == 0
        ? " Your spear kept it at bay."
        : " The " + enemy.displayName() + " " + hitVerb + " you for " + bite + " damage.";
    if (player.enemyHealth > 0) {
      String hit = skill == null ? "You dealt " : skill + " dealt ";
      return hit + damage + " damage." + biteNote + weaponNote;
    }
    player.stopFighting();
    int foodGained = Math.min(enemy.foodReward(), INVENTORY_CAPACITY - player.inventoryCount());
    player.food += foodGained;
    String reward = enemy == SurviveEnemy.RAIDER ? "" : foodGained == 0
        ? " Your full inventory could not hold its food."
        : " You gathered " + foodGained + " food.";
    String skillNote = skill == null ? "" : " " + skill + " landed.";
    return "You defeated the " + enemy.displayName() + "." + skillNote + reward + biteNote + weaponNote;
  }

  private String flee(SurvivePlayer player) {
    var enemy = player.enemy;
    player.stopFighting();
    player.takeDamage(enemy.bite());
    return "You fled from the " + enemy.displayName() + " to camp, taking " + enemy.bite() + " damage on the way.";
  }

  private String eat(SurvivePlayer player) {
    player.food--;
    player.hunger = Math.min(SurvivePlayer.MAX_HUNGER, player.hunger + 5);
    player.health = Math.min(SurvivePlayer.MAX_HEALTH, player.health + 2);
    return "You ate one food and feel fuller. You recovered up to 2 health.";
  }

  private String rest(SurvivePlayer player) {
    player.health = Math.min(SurvivePlayer.MAX_HEALTH, player.health + 2);
    return "You rested in the shared shelter and recovered 2 health.";
  }

  private static String guard(SurvivePlayer player) {
    player.guarding = true;
    return "You stand guard as night approaches. You add " + player.attackDamage() + " to the camp's defense.";
  }

  private static String sleep(SurvivePlayer player, Instant bedtime) {
    player.fallAsleep(bedtime);
    return "You lie down in a shelter bed. Sleep until dawn to recover fully, or wake early.";
  }

  private static String wakeEarly(SurvivePlayer player, Instant now) {
    long minutesAsleep = Duration.between(player.sleptAt, now).toMinutes();
    int healthEarned = (int) Math.min(SurvivePlayer.MAX_HEALTH, minutesAsleep / SLEEP_PER_HEALTH.toMinutes());
    int recovered = player.wakeAndRecover(healthEarned);
    return "You woke early and recovered " + recovered + " health.";
  }

  private String escape(SurvivePlayer player) {
    boats--;
    player.guarding = false;
    player.status = Status.ESCAPED;
    return "You sailed away safely. You survived! The camp remains for the other survivors.";
  }

  private int bedsInUse() {
    return (int) players.values().stream().filter(survivor -> survivor.status == Status.SLEEPING).count();
  }

  /** Raids grow through a seven-night week, from strength 4 up to 16, then begin again. */
  private static int raidStrengthOn(long night) {
    return 4 + 2 * (int) Math.floorMod(night, NIGHTS_PER_RAID_WEEK);
  }

  private void raidAtNightfall(long night) {
    int raidStrength = raidStrengthOn(night);
    var guards = players.values().stream()
        .filter(survivor -> survivor.guarding && survivor.status == Status.EXPLORING).toList();
    int defense = guards.stream().mapToInt(SurvivePlayer::attackDamage).sum();
    String nightName = "Night " + (night + 1);
    if (defense >= raidStrength) {
      for (var guard : guards) {
        rewardGuard(guard);
      }
      recordEvent(nightName + ": " + survivorCount(guards.size()) + " on guard repelled raiders of strength "
          + raidStrength + ".");
      return;
    }
    int structuresToDestroy = 1 + (raidStrength - defense) / 3;
    int sheltersDestroyed = 0;
    int boatsDestroyed = 0;
    for (int count = 0; count < structuresToDestroy && shelters + boats > 0; count++) {
      if (boats > shelters) {
        boats--;
        boatsDestroyed++;
      }
      else {
        shelters--;
        sheltersDestroyed++;
      }
    }
    for (var guard : guards) {
      guard.message = "Raiders of strength " + raidStrength + " overpowered the camp's defense of " + defense + ".";
      guard.revision++;
    }
    wakeSleepersWithoutBeds();
    String losses = sheltersDestroyed + boatsDestroyed == 0
        ? "found nothing to burn"
        : "destroyed " + countOf(sheltersDestroyed, "shelter") + " and " + countOf(boatsDestroyed, "boat");
    recordEvent(nightName + ": raiders of strength " + raidStrength + " overran a defense of " + defense
        + " and " + losses + ".");
  }

  private static void rewardGuard(SurvivePlayer guard) {
    if (guard.hasInventorySpace()) {
      guard.food++;
      guard.message = "You helped repel the raiders and claimed 1 food from their supplies.";
    }
    else {
      guard.gainExperience();
      guard.message = "You helped repel the raiders and grew more experienced.";
    }
    guard.revision++;
  }

  /** Sleepers who lost their bed to fire wake injured, the most recent to lie down first. */
  private void wakeSleepersWithoutBeds() {
    var sleepersByLatestBedtime = players.values().stream()
        .filter(survivor -> survivor.status == Status.SLEEPING)
        .sorted(Comparator.comparing((SurvivePlayer survivor) -> survivor.sleptAt).reversed())
        .toList();
    int sleepersWithoutBeds = Math.max(0, sleepersByLatestBedtime.size() - shelters * BEDS_PER_SHELTER);
    for (var sleeper : sleepersByLatestBedtime.subList(0, sleepersWithoutBeds)) {
      sleeper.wakeAndRecover(0);
      sleeper.takeDamage(RAID_INJURY_TO_SLEEPERS);
      sleeper.message = sleeper.status == Status.DEAD
          ? "Raiders burned your shelter while you slept. You did not survive the fire."
          : "Raiders burned your shelter while you slept. You escaped with " + RAID_INJURY_TO_SLEEPERS + " damage.";
      sleeper.revision++;
    }
  }

  private void wakeEveryoneAtDawn(long night) {
    int sleepersWoken = 0;
    for (var survivor : players.values()) {
      if (survivor.guarding) {
        survivor.guarding = false;
        survivor.revision++;
      }
      if (survivor.status == Status.SLEEPING) {
        int recovered = survivor.wakeAndRecover(SurvivePlayer.MAX_HEALTH);
        survivor.message = "Dawn broke. You woke in the shelter and recovered " + recovered + " health.";
        survivor.revision++;
        sleepersWoken++;
      }
    }
    if (sleepersWoken > 0) {
      recordEvent("Day " + (night + 2) + " dawned: " + survivorCount(sleepersWoken) + " woke in the shelters.");
    }
  }

  private static String survivorCount(int count) {
    return countOf(count, "survivor");
  }

  private static String countOf(int count, String singular) {
    return count + " " + (count == 1 ? singular : singular + "s");
  }
}
