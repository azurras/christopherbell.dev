package dev.christopherbell.survive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveEnemy;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import dev.christopherbell.survive.model.SurviveWeapon;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SurviveWorldTest {
  private static final Instant DAY = SurviveClock.WORLD_EPOCH;
  private static final Instant DUSK = DAY.plus(Duration.ofMinutes(15));
  private static final Instant NIGHTFALL = DAY.plus(Duration.ofMinutes(18));
  private static final Instant NIGHT = DAY.plus(Duration.ofMinutes(19));
  private static final Instant DAWN = DAY.plus(Duration.ofMinutes(24));
  private final SurviveWorld world = new SurviveWorld();
  private final SurvivePlayer player = new SurvivePlayer("Chris", Instant.EPOCH);

  @Test
  void gatheringUsesChanceCapacityAndExponentialProgression() {
    perform(SurviveAction.GATHER, 51);
    assertEquals(0, player.wood);
    perform(SurviveAction.GATHER, 50);
    assertEquals(1, player.wood);
    assertEquals(3, player.strength);
    assertEquals(11, player.stamina);
    assertEquals(2, player.experienceToNextLevel);
    perform(SurviveAction.GATHER, 1);
    assertEquals(3, player.strength);
    perform(SurviveAction.GATHER, 1);
    assertEquals(4, player.strength);
    assertEquals(4, player.experienceToNextLevel);
    for (int count = 0; count < 30; count++) {
      // Keep this survivor fed so the original gathering rules are observed in isolation.
      player.hunger = SurvivePlayer.MAX_HUNGER;
      perform(SurviveAction.GATHER, 1);
    }
    assertEquals(10, player.wood);
    assertEquals(30, world.snapshotFor(player, DAY, Instant.MIN).events().size());
    assertTrue(player.message.contains("full"));
  }

  @Test
  void constructionSpendsWoodAndEscapeConsumesOneSharedBoat() {
    assertFalse(actionsAt(DAY).contains(SurviveAction.BUILD_SHELTER));
    assertFalse(actionsAt(DAY).contains(SurviveAction.BUILD_BOAT));
    assertFalse(actionsAt(DAY).contains(SurviveAction.REST));
    player.wood = 5;
    perform(SurviveAction.BUILD_SHELTER, 1);
    assertEquals(0, player.wood);
    assertEquals(1, world.snapshotFor(player, DAY, Instant.MIN).shelters());
    player.health = 7;
    perform(SurviveAction.REST, 1);
    assertEquals(9, player.health);
    perform(SurviveAction.REST, 1);
    assertEquals(10, player.health);
    player.wood = 10;
    perform(SurviveAction.BUILD_BOAT, 1);
    assertEquals(0, player.wood);
    assertEquals(1, world.snapshotFor(player, DAY, Instant.MIN).boats());
    perform(SurviveAction.ESCAPE, 1);
    assertEquals(Status.ESCAPED, player.status);
    assertEquals(0, world.snapshotFor(player, DAY, Instant.MIN).boats());
    assertTrue(actionsAt(DAY).isEmpty());
  }

  @Test
  void combatDefendFleeFoodAndFreshEnemiesWork() {
    perform(SurviveAction.HUNT, 39);
    assertEquals(Status.EXPLORING, player.status);
    perform(SurviveAction.HUNT, 40);
    assertEquals(SurviveEnemy.HOG, player.enemy);
    assertEquals(5, player.enemyHealth);
    assertFalse(actionsAt(DAY).contains(SurviveAction.GATHER));
    perform(SurviveAction.DEFEND, 1);
    assertEquals(10, player.health);
    perform(SurviveAction.ATTACK, 1);
    assertEquals(2, player.enemyHealth);
    assertEquals(9, player.health);
    perform(SurviveAction.ATTACK, 1);
    assertEquals(Status.EXPLORING, player.status);
    assertNull(player.enemy);
    assertEquals(1, player.food);
    assertEquals(8, player.health);
    perform(SurviveAction.EAT, 1);
    assertEquals(0, player.food);
    assertEquals(10, player.health);
    perform(SurviveAction.HUNT, 100);
    assertEquals(5, player.enemyHealth);
    perform(SurviveAction.FLEE, 1);
    assertEquals(9, player.health);
    assertEquals(0, player.enemyHealth);
    assertEquals(Status.EXPLORING, player.status);
  }

  @Test
  void fullInventoryDoesNotGainFoodAndFatalExchangeEndsTheSurvivor() {
    player.wood = 10;
    perform(SurviveAction.HUNT, 100);
    perform(SurviveAction.ATTACK, 1);
    perform(SurviveAction.ATTACK, 1);
    assertEquals(0, player.food);
    assertEquals(10, player.wood);
    assertTrue(player.message.contains("full inventory"));
    player.health = 1;
    perform(SurviveAction.HUNT, 100);
    perform(SurviveAction.ATTACK, 1);
    assertEquals(Status.DEAD, player.status);
    assertEquals(0, player.health);
    assertTrue(actionsAt(DAY).isEmpty());
  }

  @Test
  void snapshotsDoNotExposeMutableWorldCollections() {
    var snapshot = world.snapshotFor(player, DAY, Instant.MIN);
    assertThrows(UnsupportedOperationException.class, () -> snapshot.events().add("injected"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.actions().clear());
  }

  @Test
  void huntingAfterDarkFindsWolvesAndBears() {
    performAt(SurviveAction.HUNT, DUSK, 50, 40, 51);
    assertEquals(SurviveEnemy.WOLF, player.enemy);
    assertEquals(8, player.enemyHealth);
    perform(SurviveAction.FLEE, 1);
    performAt(SurviveAction.HUNT, NIGHT, 50, 40, 85);
    assertEquals(SurviveEnemy.WOLF, player.enemy);
    perform(SurviveAction.FLEE, 1);
    performAt(SurviveAction.HUNT, NIGHT, 50, 40, 86);
    assertEquals(SurviveEnemy.BEAR, player.enemy);
    assertEquals(14, player.enemyHealth);
    perform(SurviveAction.FLEE, 1);
    performAt(SurviveAction.HUNT, DAY, 50, 40, 100);
    assertEquals(SurviveEnemy.HOG, player.enemy);
  }

  @Test
  void ambushesInterruptOutsideActionsMoreOftenAfterDark() {
    performAt(SurviveAction.GATHER, DAY, 95, 50);
    assertEquals(1, player.wood);
    performAt(SurviveAction.GATHER, DAY, 96);
    assertEquals(SurviveEnemy.HOG, player.enemy);
    assertEquals(1, player.wood);
    assertTrue(player.message.contains("ambushes"));
    perform(SurviveAction.FLEE, 1);
    performAt(SurviveAction.GATHER_STONE, DUSK, 88, 40);
    assertEquals(1, player.stone);
    performAt(SurviveAction.GATHER_STONE, DUSK, 89);
    assertEquals(SurviveEnemy.WOLF, player.enemy);
    perform(SurviveAction.FLEE, 1);
    performAt(SurviveAction.HUNT, NIGHT, 76, 80);
    assertEquals(SurviveEnemy.WOLF, player.enemy);
    perform(SurviveAction.FLEE, 1);
    performAt(SurviveAction.HUNT, NIGHT, 76, 81);
    assertEquals(SurviveEnemy.BEAR, player.enemy);
  }

  @Test
  void strongerEnemiesBiteHarderAndFleeingCostsTheirBite() {
    performAt(SurviveAction.HUNT, NIGHT, 50, 40, 86);
    perform(SurviveAction.DEFEND, 1);
    assertEquals(10, player.health);
    perform(SurviveAction.ATTACK, 1);
    assertEquals(11, player.enemyHealth);
    assertEquals(7, player.health);
    perform(SurviveAction.FLEE, 1);
    assertEquals(4, player.health);
    assertEquals(Status.EXPLORING, player.status);
  }

  @Test
  void defeatingABearYieldsAsMuchFoodAsFits() {
    player.wood = 8;
    player.strength = 20;
    performAt(SurviveAction.HUNT, NIGHT, 50, 40, 86);
    perform(SurviveAction.ATTACK, 1);
    assertEquals(Status.EXPLORING, player.status);
    assertEquals(2, player.food);
    assertTrue(player.message.contains("bear"));
  }

  @Test
  void stoneSharesTheInventoryAndCraftingRequiresSupplies() {
    perform(SurviveAction.GATHER_STONE, 41);
    assertEquals(0, player.stone);
    perform(SurviveAction.GATHER_STONE, 40);
    assertEquals(1, player.stone);
    assertEquals(3, player.strength);
    assertFalse(actionsAt(DAY).contains(SurviveAction.CRAFT_CLUB));
    player.wood = 9;
    assertFalse(player.hasInventorySpace());
    perform(SurviveAction.GATHER_STONE, 1);
    assertEquals(1, player.stone);
    assertTrue(actionsAt(DAY).contains(SurviveAction.CRAFT_CLUB));
    assertFalse(actionsAt(DAY).contains(SurviveAction.CRAFT_AXE));
    player.stone = 3;
    player.wood = 3;
    assertTrue(actionsAt(DAY).containsAll(List.of(SurviveAction.CRAFT_AXE, SurviveAction.CRAFT_SPEAR)));
    perform(SurviveAction.CRAFT_SPEAR, 1);
    assertEquals(SurviveWeapon.SPEAR, player.weapon);
    assertEquals(12, player.weaponDurability);
    assertEquals(0, player.wood);
    assertEquals(0, player.stone);
  }

  @Test
  void weaponsAddDamageWearOutAndBreak() {
    player.wood = 2;
    perform(SurviveAction.CRAFT_CLUB, 1);
    assertEquals(SurviveWeapon.CLUB, player.weapon);
    assertEquals(8, player.weaponDurability);
    performAt(SurviveAction.HUNT, NIGHT, 50, 40, 86);
    perform(SurviveAction.ATTACK, 1);
    assertEquals(10, player.enemyHealth);
    assertEquals(7, player.weaponDurability);
    player.weaponDurability = 1;
    perform(SurviveAction.ATTACK, 1);
    assertNull(player.weapon);
    assertEquals(0, player.weaponDurability);
    assertTrue(player.message.contains("club broke"));
  }

  @Test
  void stoneAxeImprovesWoodGathering() {
    player.wood = 2;
    player.stone = 2;
    perform(SurviveAction.CRAFT_AXE, 1);
    perform(SurviveAction.GATHER, 70);
    assertEquals(1, player.wood);
    perform(SurviveAction.GATHER, 71);
    assertEquals(1, player.wood);
  }

  @Test
  void spearReachSparesTheFirstBiteOfEachFight() {
    player.wood = 3;
    player.stone = 3;
    perform(SurviveAction.CRAFT_SPEAR, 1);
    performAt(SurviveAction.HUNT, NIGHT, 50, 40, 86);
    perform(SurviveAction.ATTACK, 1);
    assertEquals(10, player.health);
    assertEquals(8, player.enemyHealth);
    perform(SurviveAction.ATTACK, 1);
    assertEquals(7, player.health);
    perform(SurviveAction.FLEE, 1);
    performAt(SurviveAction.HUNT, DAY, 50, 40);
    perform(SurviveAction.ATTACK, 1);
    assertEquals(4, player.health);
    assertEquals(Status.EXPLORING, player.status);
  }

  @Test
  void effortDrainsHungerAndStarvationHurts() {
    perform(SurviveAction.GATHER, 50);
    assertEquals(9, player.hunger);
    player.wood = 5;
    perform(SurviveAction.BUILD_SHELTER, 1);
    assertEquals(9, player.hunger);
    player.hunger = 0;
    perform(SurviveAction.GATHER, 50);
    assertEquals(9, player.health);
    assertTrue(player.message.contains("starving"));
    player.health = 1;
    perform(SurviveAction.GATHER, 50);
    assertEquals(Status.DEAD, player.status);
  }

  @Test
  void eatingRestoresFullnessAndSomeHealth() {
    player.food = 1;
    assertFalse(actionsAt(DAY).contains(SurviveAction.EAT));
    player.hunger = 4;
    player.health = 9;
    assertTrue(actionsAt(DAY).contains(SurviveAction.EAT));
    perform(SurviveAction.EAT, 1);
    assertEquals(9, player.hunger);
    assertEquals(10, player.health);
    assertEquals(0, player.food);
  }

  @Test
  void guardsAtDuskRepelARaidWhenStrongEnough() {
    world.players.put("chris", player);
    world.settleUntil(DUSK);
    assertFalse(actionsAt(DAY).contains(SurviveAction.GUARD));
    player.wood = 2;
    perform(SurviveAction.CRAFT_CLUB, 1);
    performAt(SurviveAction.GUARD, DUSK, 1);
    assertTrue(player.guarding);
    assertEquals(9, player.hunger);
    assertFalse(actionsAt(DUSK).contains(SurviveAction.GUARD));
    world.settleUntil(NIGHT);
    assertEquals(1, player.food);
    assertTrue(lastEvent().contains("repelled"));
    world.settleUntil(DAWN);
    assertFalse(player.guarding);
  }

  @Test
  void anUndefendedRaidDestroysStructuresByShortfall() {
    world.players.put("chris", player);
    world.settleUntil(DUSK);
    player.wood = 10;
    perform(SurviveAction.BUILD_SHELTER, 1);
    perform(SurviveAction.BUILD_SHELTER, 1);
    player.wood = 10;
    perform(SurviveAction.BUILD_BOAT, 1);
    world.settleUntil(NIGHTFALL);
    var camp = world.snapshotFor(player, NIGHT, Instant.MIN);
    assertEquals(0, camp.shelters());
    assertEquals(1, camp.boats());
    assertTrue(lastEvent().contains("destroyed 2 shelters"));
  }

  @Test
  void escapingWhileOnGuardLeavesASaveableSurvivor() {
    world.players.put("4c0f8f3e-4a43-4f63-8d55-3b8f3c9cf4b1", player);
    player.wood = 10;
    perform(SurviveAction.BUILD_BOAT, 1);
    performAt(SurviveAction.GUARD, DUSK, 1);
    performAt(SurviveAction.ESCAPE, DUSK, 1);
    assertEquals(Status.ESCAPED, player.status);
    assertFalse(player.guarding);
    assertEquals(1, world.saveState(null).players().size());
  }

  @Test
  void leavingCampEndsGuardDuty() {
    performAt(SurviveAction.GUARD, DUSK, 1);
    performAt(SurviveAction.GATHER, DUSK, 50, 50);
    assertFalse(player.guarding);
  }

  @Test
  void sheltersHoldTwoSleepersWhoHealByTimeAndFullyAtDawn() {
    var alice = new SurvivePlayer("Alice", Instant.EPOCH);
    var bob = new SurvivePlayer("Bob", Instant.EPOCH);
    for (var survivor : List.of(player, alice, bob)) {
      world.players.put(survivor.name, survivor);
    }
    world.settleUntil(NIGHT);
    assertFalse(actionsAt(NIGHT).contains(SurviveAction.SLEEP));
    player.wood = 5;
    perform(SurviveAction.BUILD_SHELTER, 1);
    assertFalse(actionsAt(DAY).contains(SurviveAction.SLEEP));
    player.health = 3;
    alice.health = 3;
    performAt(SurviveAction.SLEEP, NIGHT, 1);
    world.perform(SurviveAction.SLEEP, alice, NIGHT, () -> 1);
    assertFalse(world.actionsFor(bob, NIGHT).contains(SurviveAction.SLEEP));
    assertEquals(List.of(SurviveAction.WAKE), actionsAt(NIGHT));
    assertEquals(2, world.snapshotFor(bob, NIGHT, Instant.MIN).bedsInUse());
    performAt(SurviveAction.WAKE, NIGHT.plus(Duration.ofMinutes(4)), 1);
    assertEquals(Status.EXPLORING, player.status);
    assertEquals(5, player.health);
    assertEquals(8, player.hunger);
    world.settleUntil(DAWN);
    assertEquals(Status.EXPLORING, alice.status);
    assertEquals(10, alice.health);
    assertEquals(8, alice.hunger);
    assertTrue(lastEvent().contains("woke"));
  }

  @Test
  void starvingSleepersDoNotHeal() {
    world.players.put("chris", player);
    world.settleUntil(NIGHT);
    player.wood = 5;
    perform(SurviveAction.BUILD_SHELTER, 1);
    player.health = 4;
    player.hunger = 0;
    performAt(SurviveAction.SLEEP, NIGHT, 1);
    world.settleUntil(DAWN);
    assertEquals(Status.EXPLORING, player.status);
    assertEquals(4, player.health);
    assertEquals(0, player.hunger);
  }

  @Test
  void aBurnedShelterWakesItsLatestSleeperWithInjury() {
    var alice = new SurvivePlayer("Alice", Instant.EPOCH);
    var bob = new SurvivePlayer("Bob", Instant.EPOCH);
    var guard = new SurvivePlayer("Guard", Instant.EPOCH);
    for (var survivor : List.of(player, alice, bob, guard)) {
      world.players.put(survivor.name, survivor);
    }
    world.settleUntil(DUSK);
    player.wood = 10;
    perform(SurviveAction.BUILD_SHELTER, 1);
    perform(SurviveAction.BUILD_SHELTER, 1);
    world.perform(SurviveAction.GUARD, guard, DUSK, () -> 1);
    world.perform(SurviveAction.SLEEP, alice, DUSK, () -> 1);
    world.perform(SurviveAction.SLEEP, bob, DUSK.plusSeconds(60), () -> 1);
    performAt(SurviveAction.SLEEP, DUSK.plusSeconds(120), 1);
    world.settleUntil(NIGHTFALL);
    assertEquals(1, world.snapshotFor(player, NIGHT, Instant.MIN).shelters());
    assertEquals(Status.EXPLORING, player.status);
    assertEquals(8, player.health);
    assertEquals(Status.SLEEPING, alice.status);
    assertEquals(Status.SLEEPING, bob.status);
  }

  @Test
  void firstSettlementStartsTheClockAndMissedNightsAreCapped() {
    world.players.put("chris", player);
    world.settleUntil(DAY.plus(Duration.ofMinutes(240)));
    assertTrue(world.snapshotFor(player, DAY, Instant.MIN).events().isEmpty());
    world.settleUntil(DAY.plus(Duration.ofMinutes(480)));
    var events = world.snapshotFor(player, DAY, Instant.MIN).events();
    assertEquals(4, events.size());
    assertTrue(events.getFirst().contains("quietly"));
    world.settleUntil(DAY.plus(Duration.ofMinutes(480)));
    assertEquals(4, world.snapshotFor(player, DAY, Instant.MIN).events().size());
  }

  @Test
  void savedStateRetainsEveryNewSurvivorField() {
    String guestToken = "4c0f8f3e-4a43-4f63-8d55-3b8f3c9cf4b1";
    world.players.put(guestToken, player);
    world.settleUntil(DUSK);
    player.wood = 9;
    perform(SurviveAction.BUILD_SHELTER, 1);
    perform(SurviveAction.CRAFT_CLUB, 1);
    player.stone = 1;
    performAt(SurviveAction.SLEEP, DUSK, 1);
    var savedWorld = world.saveState(null);
    var restoredWorld = SurviveWorld.restore(savedWorld);
    var restored = restoredWorld.players.get(guestToken);
    assertEquals(Status.SLEEPING, restored.status);
    assertEquals(DUSK, restored.sleptAt);
    assertEquals(SurviveWeapon.CLUB, restored.weapon);
    assertEquals(8, restored.weaponDurability);
    assertEquals(1, restored.stone);
    assertEquals(player.hunger, restored.hunger);
    assertEquals(savedWorld, restoredWorld.saveState(null));
  }

  private List<SurviveAction> actionsAt(Instant now) {
    return world.actionsFor(player, now);
  }

  private String lastEvent() {
    return world.snapshotFor(player, DAY, Instant.MIN).events().getLast();
  }

  private void perform(SurviveAction action, int rollPercent) {
    world.perform(action, player, DAY, () -> rollPercent);
  }

  /** Supplies each roll in order, then repeats the last, so tests name every random decision. */
  private void performAt(SurviveAction action, Instant now, int... rollPercents) {
    var rolls = new ArrayDeque<Integer>();
    IntStream.of(rollPercents).forEach(rolls::addLast);
    int finalRoll = rollPercents[rollPercents.length - 1];
    IntSupplier nextRoll = () -> rolls.isEmpty() ? finalRoll : rolls.removeFirst();
    world.perform(action, player, now, nextRoll);
  }
}
