package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveMovement;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import dev.christopherbell.survive.model.SurviveWaystation;
import dev.christopherbell.survive.model.SurviveWeapon;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Transient island positions, owned under SurviveService's monitor, independent of durable saves. */
final class SurvivePresence {
  private static final Duration VISIBLE_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration SESSION_TIMEOUT = Duration.ofHours(2);
  private static final int CAPACITY = 1000;
  private static final double SPEED_PER_SECOND = 6.5;
  // The renderer draws players out to 60 m. This margin lets it interpolate boundary crossings.
  private static final double POSE_RADIUS_METRES = 64;
  private final Map<String, TrackedPose> poses = new HashMap<>();

  View pulse(String survivorId, String name, Status status, SurviveMovement movement, Instant now) {
    return pulse(survivorId, name, status, movement, now, null);
  }

  View pulse(String survivorId, String name, Status status, SurviveMovement movement, Instant now,
      SurviveWildlife.Animal savedEncounter) {
    var sessionDeadline = now.minus(SESSION_TIMEOUT);
    var visibleDeadline = now.minus(VISIBLE_TIMEOUT);
    poses.values().removeIf(pose -> !pose.seenAt().isAfter(sessionDeadline));
    var previous = poses.get(survivorId);
    if (previous == null && poses.size() >= CAPACITY) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "The island is full. Try again later.");
    }
    boolean reconnecting = previous == null || !previous.seenAt().isAfter(visibleDeadline);
    boolean accepted = true;
    TrackedPose current;
    if (reconnecting) {
      double x = savedEncounter == null ? 0 : savedEncounter.x();
      double z = savedEncounter == null ? 0 : savedEncounter.z();
      current = new TrackedPose(new Pose(survivorId, name, x, z, 0, movement.sequence(), status), now, now);
    }
    else if (movement.sequence() <= previous.pose().sequence()) {
      current = previous;
      accepted = false;
    }
    else {
      double elapsedSeconds = Math.max(0, Math.min(1.5,
          Duration.between(previous.movedAt(), now).toMillis() / 1000.0));
      double distance = Math.hypot(movement.x() - previous.pose().x(), movement.z() - previous.pose().z());
      boolean canMove = status == Status.EXPLORING;
      accepted = distance <= SPEED_PER_SECOND * elapsedSeconds + 0.05 && (canMove || distance < 0.001);
      current = new TrackedPose(new Pose(survivorId, name,
          accepted ? movement.x() : previous.pose().x(), accepted ? movement.z() : previous.pose().z(),
          accepted ? movement.heading() : previous.pose().heading(), movement.sequence(), status), now, now);
    }
    poses.put(survivorId, current);
    var acknowledged = current.pose();
    var others = new ArrayList<Pose>();
    int onlineCount = 0;
    for (var tracked : poses.values()) {
      if (!tracked.seenAt().isAfter(visibleDeadline)) {
        continue;
      }
      onlineCount++;
      var pose = tracked.pose();
      double dx = pose.x() - acknowledged.x();
      double dz = pose.z() - acknowledged.z();
      if (!pose.survivorId().equals(survivorId) && dx * dx + dz * dz <= POSE_RADIUS_METRES * POSE_RADIUS_METRES) {
        others.add(pose);
      }
    }
    return new View(acknowledged, others, accepted, List.of(), null, onlineCount);
  }

  Pose activePose(String survivorId, Instant now) {
    var tracked = poses.get(survivorId);
    return tracked != null && tracked.seenAt().isAfter(now.minus(VISIBLE_TIMEOUT)) ? tracked.pose() : null;
  }

  boolean tracks(String survivorId) {
    return poses.containsKey(survivorId);
  }

  /** Older text clients retain their rules; spatial sessions execute at their last verified landmark. */
  boolean permits(String survivorId, SurviveAction action, Instant now) {
    if (action == SurviveAction.TALK_WAYSTATION) {
      var pose = activePose(survivorId, now);
      var keeper = SurviveWaystation.KEEPER;
      return pose != null && near(pose.x(), pose.z(), keeper.x(), keeper.z(), keeper.talkRange());
    }
    var tracked = poses.get(survivorId);
    if (tracked == null) {
      return true;
    }
    var pose = tracked.pose();
    boolean active = tracked.seenAt().isAfter(now.minus(VISIBLE_TIMEOUT));
    double x = active ? pose.x() : 0;
    double z = active ? pose.z() : 0;
    return switch (action) {
      case GATHER -> near(x, z, -24, -15, 13);
      case GATHER_STONE -> near(x, z, 30, -12, 12);
      case HUNT -> true;
      case FISH -> near(x, z, -13, 70, 7);
      case BUILD_BOAT, ESCAPE -> near(x, z, 0, 62, 13);
      case BUILD_SHELTER, REST, GUARD, SLEEP, CRAFT_CLUB, CRAFT_AXE, CRAFT_SPEAR -> near(x, z, 0, 0, 15);
      case EAT, ATTACK, ENGAGE, DISENGAGE, WEAPON_SKILL, DEFEND, FLEE, WAKE, TALK_WAYSTATION, EQUIP_WEAPON, STOW_WEAPON -> true;
    };
  }

  void returnToCamp(String survivorId) {
    var previous = poses.get(survivorId);
    if (previous != null) {
      var pose = previous.pose();
      poses.put(survivorId, new TrackedPose(new Pose(survivorId, pose.name(), 0, 0, 0,
          pose.sequence(), Status.EXPLORING), previous.seenAt(), previous.movedAt()));
    }
  }

  void remove(String survivorId) {
    poses.remove(survivorId);
  }

  private static boolean near(double x, double z, double centerX, double centerZ, double radius) {
    return Math.hypot(x - centerX, z - centerZ) <= radius;
  }

  /** Public render data only: no cookie, account ID, or inventory. */
  public record Pose(String survivorId, String name, double x, double z, double heading, long sequence, Status status,
                     Appearance appearance) {
    Pose(String survivorId, String name, double x, double z, double heading, long sequence, Status status) {
      this(survivorId, name, x, z, heading, sequence, status, null);
    }
  }

  /** Confirmed equipment and stance for drawing another survivor. */
  public record Appearance(SurviveWeapon weapon, boolean engaged, boolean defending) {}

  /** Acknowledged pose, nearby players, and island-wide active presence count. */
  public record View(Pose self, List<Pose> others, boolean accepted, List<SurviveWildlife.Animal> wildlife,
                     SurviveWildlife.Animal encounter, int onlineCount) {
    public View {
      others = List.copyOf(others);
      wildlife = List.copyOf(wildlife);
    }

    View withScene(Status status, List<Pose> visiblePlayers, List<SurviveWildlife.Animal> animals,
        SurviveWildlife.Animal target) {
      return new View(new Pose(self.survivorId(), self.name(), self.x(), self.z(), self.heading(), self.sequence(), status),
          visiblePlayers, accepted, animals, target, onlineCount);
    }
  }

  private record TrackedPose(Pose pose, Instant seenAt, Instant movedAt) {}
}
