package dev.christopherbell.survive;

import static org.junit.jupiter.api.Assertions.*;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SurviveWorldTest {
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
    for (int count = 0; count < 30; count++) perform(SurviveAction.GATHER, 1);
    assertEquals(10, player.wood);
    assertEquals(30, world.snapshotFor(player).events().size());
    assertTrue(player.message.contains("full"));
  }

  @Test
  void constructionSpendsWoodAndEscapeConsumesOneSharedBoat() {
    assertFalse(world.actionsFor(player).contains(SurviveAction.BUILD_SHELTER));
    assertFalse(world.actionsFor(player).contains(SurviveAction.BUILD_BOAT));
    assertFalse(world.actionsFor(player).contains(SurviveAction.REST));
    for (int count = 0; count < 5; count++) perform(SurviveAction.GATHER, 1);
    perform(SurviveAction.BUILD_SHELTER, 1);
    assertEquals(0, player.wood);
    assertEquals(1, world.snapshotFor(player).shelters());
    player.health = 7;
    perform(SurviveAction.REST, 1);
    assertEquals(9, player.health);
    perform(SurviveAction.REST, 1);
    assertEquals(10, player.health);
    for (int count = 0; count < 10; count++) perform(SurviveAction.GATHER, 1);
    perform(SurviveAction.BUILD_BOAT, 1);
    assertEquals(0, player.wood);
    assertEquals(1, world.snapshotFor(player).boats());
    perform(SurviveAction.ESCAPE, 1);
    assertEquals(Status.ESCAPED, player.status);
    assertEquals(0, world.snapshotFor(player).boats());
    assertTrue(world.actionsFor(player).isEmpty());
  }

  @Test
  void combatDefendFleeFoodAndFreshEnemiesWork() {
    perform(SurviveAction.HUNT, 39);
    assertEquals(Status.EXPLORING, player.status);
    perform(SurviveAction.HUNT, 40);
    assertEquals(5, player.enemyHealth);
    assertFalse(world.actionsFor(player).contains(SurviveAction.GATHER));
    perform(SurviveAction.DEFEND, 1);
    assertEquals(10, player.health);
    perform(SurviveAction.ATTACK, 1);
    assertEquals(2, player.enemyHealth);
    assertEquals(9, player.health);
    perform(SurviveAction.ATTACK, 1);
    assertEquals(Status.EXPLORING, player.status);
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
    assertTrue(world.actionsFor(player).isEmpty());
  }

  @Test
  void snapshotsDoNotExposeMutableWorldCollections() {
    var snapshot = world.snapshotFor(player);
    assertThrows(UnsupportedOperationException.class, () -> snapshot.events().add("injected"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.actions().clear());
  }

  private void perform(SurviveAction action, int rollPercent) {
    world.perform(action, player, () -> rollPercent);
  }
}
