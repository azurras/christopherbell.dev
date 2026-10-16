package dev.christopherbell.survive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.christopherbell.survive.model.SurviveSnapshot.Phase;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SurviveClockTest {
  private static final Instant DAWN_OF_DAY_ONE = SurviveClock.WORLD_EPOCH;

  @Test
  void everySurvivorSeesTheSamePhaseFromTheWallClock() {
    assertEquals(Phase.DAY, SurviveClock.phaseAt(DAWN_OF_DAY_ONE));
    assertEquals(Phase.DAY, SurviveClock.phaseAt(minutesAfterDawn(13).plusSeconds(59)));
    assertEquals(Phase.DUSK, SurviveClock.phaseAt(minutesAfterDawn(14)));
    assertEquals(Phase.NIGHT, SurviveClock.phaseAt(minutesAfterDawn(18)));
    assertEquals(Phase.DAY, SurviveClock.phaseAt(minutesAfterDawn(24)));
    assertEquals(1, SurviveClock.dayAt(minutesAfterDawn(23)));
    assertEquals(2, SurviveClock.dayAt(minutesAfterDawn(24)));
  }

  @Test
  void countdownReachesTheNextPhaseBoundary() {
    assertEquals(840, SurviveClock.secondsToNextPhaseAt(DAWN_OF_DAY_ONE));
    assertEquals(1, SurviveClock.secondsToNextPhaseAt(minutesAfterDawn(17).plusSeconds(59)));
    assertEquals(360, SurviveClock.secondsToNextPhaseAt(minutesAfterDawn(18)));
  }

  @Test
  void instantsBeforeTheWorldEpochStillFollowTheCycle() {
    var dawnTwoCyclesEarlier = DAWN_OF_DAY_ONE.minus(Duration.ofMinutes(48));
    assertEquals(Phase.DAY, SurviveClock.phaseAt(dawnTwoCyclesEarlier));
    assertEquals(Phase.NIGHT, SurviveClock.phaseAt(DAWN_OF_DAY_ONE.minusSeconds(1)));
    assertEquals(-1, SurviveClock.dayAt(dawnTwoCyclesEarlier));
  }

  @Test
  void boundariesAlternateNightfallAndDawnInOrder() {
    assertEquals(0, SurviveClock.boundariesPassedBy(DAWN_OF_DAY_ONE));
    assertEquals(1, SurviveClock.boundariesPassedBy(minutesAfterDawn(18)));
    assertEquals(2, SurviveClock.boundariesPassedBy(minutesAfterDawn(24)));
    assertEquals(3, SurviveClock.boundariesPassedBy(minutesAfterDawn(42)));
    assertEquals(minutesAfterDawn(18), SurviveClock.boundaryInstant(0));
    assertEquals(minutesAfterDawn(24), SurviveClock.boundaryInstant(1));
    assertEquals(minutesAfterDawn(42), SurviveClock.boundaryInstant(2));
    assertTrue(SurviveClock.isNightfall(2));
    assertFalse(SurviveClock.isNightfall(3));
    assertEquals(1, SurviveClock.nightOf(3));
  }

  private static Instant minutesAfterDawn(long minutes) {
    return DAWN_OF_DAY_ONE.plus(Duration.ofMinutes(minutes));
  }
}
