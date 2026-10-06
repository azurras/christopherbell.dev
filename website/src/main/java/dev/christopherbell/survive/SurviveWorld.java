package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveSnapshot;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import dev.christopherbell.survive.model.SurviveSnapshot.Recipient;
import dev.christopherbell.survive.model.SurviveResource;
import java.util.Locale;
import java.time.Instant;
import dev.christopherbell.survive.model.SurviveSavedWorld;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * One authoritative world: survivors own their supplies while camp structures and events are shared.
 * Adapted from azurras/survive (GPL-3.0); see README.md for source and completed mechanics.
 * The service serializes all access, allowing a future transport to reuse the same commands.
 */
final class SurviveWorld {
  static final int INVENTORY_CAPACITY = 10;
  private static final int MAX_EVENTS = 30;
  private static final int MAX_STRUCTURES = 1000;
  final Map<String, SurvivePlayer> players = new LinkedHashMap<>();
  private final ArrayDeque<String> events = new ArrayDeque<>();
  private int shelters;
  private int boats;
  private long revision;

  void recordEvent(String event) {
    if (events.size() == MAX_EVENTS) events.removeFirst();
    events.addLast(event);
    revision++;
  }

  static SurviveWorld restore(SurviveSavedWorld saved) {
    var world = new SurviveWorld();
    world.shelters = saved.shelters();
    world.boats = saved.boats();
    world.revision = saved.revision();
    world.events.addAll(saved.events());
    for (var stored : saved.players()) {
      var player = new SurvivePlayer(stored.name(), stored.lastSeen(), stored.accountId(), stored.survivorId());
      player.health = stored.health(); player.strength = stored.strength(); player.stamina = stored.stamina();
      player.experience = stored.experience(); player.experienceToNextLevel = stored.experienceToNextLevel();
      player.wood = stored.wood(); player.food = stored.food(); player.enemyHealth = stored.enemyHealth();
      player.status = stored.status(); player.revision = stored.revision(); player.message = stored.message();
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
          player.status, player.revision, player.message);
    }).toList();
    return new SurviveSavedWorld("shared", storageVersion, revision, shelters, boats, List.copyOf(events), storedPlayers);
  }

  SurviveSnapshot snapshotFor(SurvivePlayer player) {
    return snapshotFor(player, Instant.MIN);
  }

  SurviveSnapshot snapshotFor(SurvivePlayer player, Instant activeDeadline) {
    return new SurviveSnapshot(player.name, player.health, player.strength, player.stamina,
        player.experience, player.experienceToNextLevel, player.wood, player.food,
        INVENTORY_CAPACITY, player.enemyHealth, player.status, player.revision, revision,
        shelters, boats, players.values().stream().filter(survivor -> survivor.lastSeen.isAfter(activeDeadline)).map(survivor -> survivor.name).toList(),
        List.copyOf(events), actionsFor(player), player.message, player.survivorId,
        player.status == Status.EXPLORING ? players.values().stream()
            .filter(recipient -> recipient != player && recipient.status == Status.EXPLORING && recipient.lastSeen.isAfter(activeDeadline))
            .map(recipient -> new Recipient(recipient.survivorId, recipient.name)).toList() : List.of(), player.accountId != null);
  }

  /** Applies a previously validated gift while the service holds the world's monitor. */
  void giveSupplies(SurvivePlayer sender, SurvivePlayer recipient, SurviveResource resource, int quantity) {
    switch (resource) {
      case WOOD -> { sender.wood -= quantity; recipient.wood += quantity; }
      case FOOD -> { sender.food -= quantity; recipient.food += quantity; }
    }
    String supplies = quantity + " " + resource.name().toLowerCase(Locale.ROOT);
    sender.message = "You gave " + supplies + " to " + recipient.name + ".";
    recipient.message = sender.name + " gave you " + supplies + ".";
    sender.revision++;
    recipient.revision++;
    recordEvent(sender.name + " gave " + supplies + " to " + recipient.name + ".");
  }

  void perform(SurviveAction action, SurvivePlayer player, IntSupplier rollPercent) {
    String outcome = switch (action) {
      case GATHER -> gatherWood(player, rollPercent);
      case BUILD_SHELTER -> buildShelter(player);
      case BUILD_BOAT -> buildBoat(player);
      case HUNT -> hunt(player, rollPercent);
      case ATTACK -> attack(player);
      case DEFEND -> "You brace against the hog's bite and block its damage. Attack or flee next.";
      case FLEE -> flee(player);
      case EAT -> eat(player);
      case REST -> rest(player);
      case ESCAPE -> escape(player);
    };
    if (player.status == Status.DEAD) outcome += " You have died. Start a new survivor to try again.";
    player.message = outcome;
    player.revision++;
    recordEvent(player.name + ": " + outcome);
  }

  List<SurviveAction> actionsFor(SurvivePlayer player) {
    if (player.status == Status.DEAD || player.status == Status.ESCAPED) return List.of();
    if (player.status == Status.COMBAT) {
      return List.of(SurviveAction.ATTACK, SurviveAction.DEFEND, SurviveAction.FLEE);
    }
    var actions = new ArrayList<>(List.of(SurviveAction.GATHER, SurviveAction.HUNT));
    if (player.wood >= 5 && shelters < MAX_STRUCTURES) actions.add(SurviveAction.BUILD_SHELTER);
    if (player.wood >= 10 && boats < MAX_STRUCTURES) actions.add(SurviveAction.BUILD_BOAT);
    if (player.food > 0 && player.health < 10) actions.add(SurviveAction.EAT);
    if (shelters > 0 && player.health < 10) actions.add(SurviveAction.REST);
    if (boats > 0) actions.add(SurviveAction.ESCAPE);
    return List.copyOf(actions);
  }

  private String gatherWood(SurvivePlayer player, IntSupplier rollPercent) {
    if (!player.hasInventorySpace()) return "Your inventory is full. Craft or eat to make room.";
    if (rollPercent.getAsInt() > 50) return "The tree resists your efforts. No wood gathered.";
    player.wood++;
    int previousStrength = player.strength;
    player.gainGatheringExperience();
    return "You gathered one piece of wood."
        + (player.strength > previousStrength ? " Your strength and stamina increased!" : "");
  }

  private String buildShelter(SurvivePlayer player) {
    player.wood -= 5;
    shelters++;
    return "built a shelter for the camp. Every survivor can now rest here.";
  }

  private String buildBoat(SurvivePlayer player) {
    player.wood -= 10;
    boats++;
    return "built a boat for the camp. One survivor can use it to escape.";
  }

  private String hunt(SurvivePlayer player, IntSupplier rollPercent) {
    if (rollPercent.getAsInt() < 40) return "No animal found. You return to camp.";
    player.enemyHealth = 5;
    player.status = Status.COMBAT;
    return "A wild hog appears! It has 5 health. Choose attack, defend, or flee.";
  }

  private String attack(SurvivePlayer player) {
    int damage = 1 + player.strength;
    player.enemyHealth = Math.max(0, player.enemyHealth - damage);
    // Preserve the original simultaneous exchange, including the finishing blow's bite.
    player.takeDamage(1);
    if (player.status == Status.DEAD) return "You attacked the hog, but its bite was fatal.";
    if (player.enemyHealth == 0) {
      player.status = Status.EXPLORING;
      if (player.hasInventorySpace()) {
        player.food++;
        return "You defeated the hog and gathered one food. You took 1 damage.";
      }
      return "You defeated the hog and took 1 damage. Your full inventory could not hold its food.";
    }
    return "You dealt " + damage + " damage. The hog bit you for 1 damage.";
  }

  private String flee(SurvivePlayer player) {
    player.enemyHealth = 0;
    player.status = Status.EXPLORING;
    player.takeDamage(1);
    return "You fled to camp, taking 1 damage on the way.";
  }

  private String eat(SurvivePlayer player) {
    player.food--;
    player.health = Math.min(10, player.health + 5);
    return "You ate one food and recovered health.";
  }

  private String rest(SurvivePlayer player) {
    player.health = Math.min(10, player.health + 2);
    return "You rested in the shared shelter and recovered 2 health.";
  }

  private String escape(SurvivePlayer player) {
    boats--;
    player.status = Status.ESCAPED;
    return "You sailed away safely. You survived! The camp remains for the other survivors.";
  }
}
