package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveEnemy;
import dev.christopherbell.survive.model.SurviveResource;
import dev.christopherbell.survive.model.SurviveSavedWorld;
import dev.christopherbell.survive.model.SurviveSnapshot;
import dev.christopherbell.survive.model.SurviveSnapshot.Phase;
import dev.christopherbell.survive.model.SurviveSnapshot.Recipient;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import dev.christopherbell.survive.model.SurviveWeapon;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
  final Map<String, SurvivePlayer> players = new LinkedHashMap<>();
  private final ArrayDeque<String> events = new ArrayDeque<>();
  private int shelters;
  private int boats;
  private long revision;
  /** Nightfalls and dawns already applied; null until the first settlement starts the count. */
  private Long settledBoundaries;

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
    world.events.addAll(saved.events());
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
      player.enemy = stored.enemy();
      player.enemyHealth = stored.enemyHealth();
      player.struckFirst = stored.struckFirst();
      player.weapon = stored.weapon();
      player.weaponDurability = stored.weaponDurability();
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
          player.weapon, player.weaponDurability, player.guarding, player.sleptAt, player.struckFirst);
    }).toList();
    return new SurviveSavedWorld("shared", storageVersion, revision, shelters, boats, List.copyOf(events),
        storedPlayers, settledBoundaries);
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
        bedsInUse(), shelters * BEDS_PER_SHELTER, player.guarding);
  }

  /**
   * Applies every nightfall raid and dawn since the last settlement, oldest first. The first
   * settlement only starts the count, so deploying the clock never replays the past. After a long
   * quiet spell only the latest nights are applied.
   */
  void settleUntil(Instant now) {
    long boundariesPassed = SurviveClock.boundariesPassedBy(now);
    if (settledBoundaries == null) {
      settledBoundaries = boundariesPassed;
      return;
    }
    if (boundariesPassed <= settledBoundaries) {
      return;
    }
    long firstBoundaryToApply = Math.max(settledBoundaries, boundariesPassed - 2L * MAX_NIGHTS_SETTLED_AT_ONCE);
    if (firstBoundaryToApply > settledBoundaries) {
      recordEvent("Several nights passed quietly while the camp slept.");
    }
    for (long boundary = firstBoundaryToApply; boundary < boundariesPassed; boundary++) {
      long night = SurviveClock.nightOf(boundary);
      if (SurviveClock.isNightfall(boundary)) {
        raidAtNightfall(night);
      }
      else {
        wakeEveryoneAtDawn(night);
      }
    }
    settledBoundaries = boundariesPassed;
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
    var phase = SurviveClock.phaseAt(now);
    String hungerOutcome = spendEffort(action, player);
    String outcome = player.status == Status.DEAD
        ? "You starved before you could act."
        : switch (action) {
          case GATHER -> ventureOut(player, phase, rollPercent, () -> gatherWood(player, rollPercent));
          case GATHER_STONE -> ventureOut(player, phase, rollPercent, () -> gather(player, rollPercent, 40, SurviveResource.STONE));
          case HUNT -> ventureOut(player, phase, rollPercent, () -> hunt(player, phase, rollPercent));
          case BUILD_SHELTER -> buildShelter(player);
          case BUILD_BOAT -> buildBoat(player);
          case CRAFT_CLUB -> craft(player, SurviveWeapon.CLUB);
          case CRAFT_AXE -> craft(player, SurviveWeapon.STONE_AXE);
          case CRAFT_SPEAR -> craft(player, SurviveWeapon.SPEAR);
          case ATTACK -> attack(player);
          case DEFEND -> "You brace against the " + player.enemy.displayName() + " and block its bite. Attack or flee next.";
          case FLEE -> flee(player);
          case EAT -> eat(player);
          case REST -> rest(player);
          case GUARD -> guard(player);
          case SLEEP -> sleep(player, now);
          case WAKE -> wakeEarly(player, now);
          case ESCAPE -> escape(player);
        } + hungerOutcome;
    if (player.status == Status.DEAD) {
      outcome += " You have died. Start a new survivor to try again.";
    }
    player.message = outcome;
    player.revision++;
    recordEvent(player.name + ": " + outcome);
  }

  List<SurviveAction> actionsFor(SurvivePlayer player, Instant now) {
    return switch (player.status) {
      case DEAD, ESCAPED -> List.of();
      case SLEEPING -> List.of(SurviveAction.WAKE);
      case COMBAT -> List.of(SurviveAction.ATTACK, SurviveAction.DEFEND, SurviveAction.FLEE);
      case EXPLORING -> campActionsFor(player, SurviveClock.phaseAt(now));
    };
  }

  private List<SurviveAction> campActionsFor(SurvivePlayer player, Phase phase) {
    var actions = new ArrayList<>(List.of(SurviveAction.GATHER, SurviveAction.GATHER_STONE, SurviveAction.HUNT));
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

  /**
   * Effortful actions spend one fullness. A starving survivor instead loses one health, which can
   * end the survivor before the action happens. Returns any note for the outcome message.
   */
  private static String spendEffort(SurviveAction action, SurvivePlayer player) {
    boolean isEffort = switch (action) {
      case GATHER, GATHER_STONE, HUNT, ATTACK, DEFEND, FLEE, GUARD -> true;
      case BUILD_SHELTER, BUILD_BOAT, CRAFT_CLUB, CRAFT_AXE, CRAFT_SPEAR, EAT, REST, SLEEP, WAKE, ESCAPE -> false;
    };
    if (!isEffort) {
      return "";
    }
    if (player.hunger > 0) {
      player.hunger--;
      return "";
    }
    player.takeDamage(1);
    return " You are starving and lost 1 health.";
  }

  /** Leaving camp ends guard duty and risks an ambush that replaces the intended action. */
  private String ventureOut(SurvivePlayer player, Phase phase, IntSupplier rollPercent, Supplier<String> intendedAction) {
    player.guarding = false;
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
        + " health. Choose attack, defend, or flee.";
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
    player.gainGatheringExperience();
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
    String replaced = player.weapon == null ? "" : " It replaces your " + player.weapon.displayName() + ".";
    player.weapon = weapon;
    player.weaponDurability = weapon.durability();
    return "You crafted a " + weapon.displayName() + ": +" + weapon.damageBonus() + " damage for "
        + weapon.durability() + " attacks." + replaced;
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
    player.startFighting(animal);
    return "A wild " + animal.displayName() + " appears! It has " + animal.health()
        + " health. Choose attack, defend, or flee.";
  }

  private static SurviveEnemy nightAnimalFor(int rollPercent) {
    if (rollPercent <= 50) {
      return SurviveEnemy.HOG;
    }
    return rollPercent <= 85 ? SurviveEnemy.WOLF : SurviveEnemy.BEAR;
  }

  private String attack(SurvivePlayer player) {
    var enemy = player.enemy;
    int damage = player.attackDamage();
    boolean reachSparesBite = !player.struckFirst && player.weapon != null && player.weapon.keepsFirstBiteAway();
    var heldWeapon = player.weapon;
    player.struckFirst = true;
    player.enemyHealth = Math.max(0, player.enemyHealth - damage);
    String weaponNote = player.wearWeaponAndReportBreak() ? " Your " + heldWeapon.displayName() + " broke." : "";
    // Preserve the original simultaneous exchange, including the finishing blow's bite.
    int bite = reachSparesBite ? 0 : enemy.bite();
    player.takeDamage(bite);
    if (player.status == Status.DEAD) {
      return "You attacked the " + enemy.displayName() + ", but its bite was fatal." + weaponNote;
    }
    String biteNote = bite == 0
        ? " Your spear kept it at bay."
        : " The " + enemy.displayName() + " bit you for " + bite + " damage.";
    if (player.enemyHealth > 0) {
      return "You dealt " + damage + " damage." + biteNote + weaponNote;
    }
    player.stopFighting();
    int foodGained = Math.min(enemy.foodReward(), INVENTORY_CAPACITY - player.inventoryCount());
    player.food += foodGained;
    String reward = foodGained == 0
        ? " Your full inventory could not hold its food."
        : " You gathered " + foodGained + " food.";
    return "You defeated the " + enemy.displayName() + "." + reward + biteNote + weaponNote;
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
      guard.gainGatheringExperience();
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
