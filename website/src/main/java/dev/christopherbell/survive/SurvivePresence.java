package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveAction;
import dev.christopherbell.survive.model.SurviveMovement;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Transient island positions, owned under SurviveService's monitor, independent of durable saves. */
final class SurvivePresence {
  private static final Duration VISIBLE_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration SESSION_TIMEOUT = Duration.ofHours(2);
  private static final int CAPACITY = 1000;
  private static final double SPEED_PER_SECOND = 6.5;
  private final Map<String, TrackedPose> poses = new HashMap<>();

  View pulse(String survivorId, String name, Status status, SurviveMovement movement, Instant now) {
    poses.values().removeIf(pose -> !pose.seenAt().isAfter(now.minus(SESSION_TIMEOUT)));
    var previous = poses.get(survivorId);
    if (previous == null && poses.size() >= CAPACITY) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "The island is full. Try again later.");
    }
    boolean reconnecting = previous == null || !previous.seenAt().isAfter(now.minus(VISIBLE_TIMEOUT));
    boolean accepted = true;
    TrackedPose current;
    if (reconnecting) {
      current = new TrackedPose(new Pose(survivorId, name, 0, 0, 0, movement.sequence(), status), now, now);
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
    var others = poses.values().stream()
        .filter(pose -> !pose.pose().survivorId().equals(survivorId)
            && pose.seenAt().isAfter(now.minus(VISIBLE_TIMEOUT)))
        .map(TrackedPose::pose).toList();
    return new View(current.pose(), others, accepted, List.of(), null);
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
      case EAT, ATTACK, ENGAGE, DISENGAGE, WEAPON_SKILL, DEFEND, FLEE, WAKE -> true;
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

  /** Public render data only: no cookie, account ID, inventory or other private state. */
  public record Pose(String survivorId, String name, double x, double z, double heading, long sequence, Status status) {}

  /** The sender's acknowledged pose and currently visible other players. */
  public record View(Pose self, List<Pose> others, boolean accepted, List<SurviveWildlife.Animal> wildlife,
                     SurviveWildlife.Animal encounter) {
    public View {
      others = List.copyOf(others);
      wildlife = List.copyOf(wildlife);
    }

    View withWildlife(Status status, List<SurviveWildlife.Animal> animals, SurviveWildlife.Animal target) {
      return new View(new Pose(self.survivorId(), self.name(), self.x(), self.z(), self.heading(), self.sequence(), status),
          others, accepted, animals, target);
    }
  }

  private record TrackedPose(Pose pose, Instant seenAt, Instant movedAt) {}
}
