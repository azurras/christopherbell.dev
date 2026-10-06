package dev.christopherbell.survive.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import dev.christopherbell.survive.model.SurviveSnapshot.Status;

/** One bounded durable world; replacing it commits every affected inventory and camp together. */
public record SurviveSavedWorld(@Id String id, @Version Long version, long revision,
    int shelters, int boats, List<String> events, List<Player> players) {
  public SurviveSavedWorld {
    if (!"shared".equals(id) || version != null && version < 0 || revision < 0
        || shelters < 0 || shelters > 1000 || boats < 0 || boats > 1000) {
      throw new IllegalArgumentException("Saved camp values are invalid.");
    }
    events = List.copyOf(events);
    players = List.copyOf(players);
    if (events.size() > 30 || players.size() > 1000
        || events.stream().anyMatch(event -> event.length() > 1024)) {
      throw new IllegalArgumentException("Saved world exceeds its bounds.");
    }
    var owners = new HashSet<String>();
    var survivorIds = new HashSet<String>();
    for (var player : players) {
      String owner = player.accountId() == null ? "guest:" + player.token() : "account:" + player.accountId();
      if (!owners.add(owner) || !survivorIds.add(player.survivorId())) {
        throw new IllegalArgumentException("Saved world contains duplicate survivors.");
      }
    }
  }

  /** New worlds have no durable version until their first successful insert. */
  public static SurviveSavedWorld empty() {
    return new SurviveSavedWorld("shared", null, 0, 0, 0, List.of(), List.of());
  }

  /** Private ownership and all character progress; never serialized as an HTTP response. */
  public record Player(String accountId, String token, String survivorId, String name,
      Instant lastSeen, int health, int strength, int stamina, int experience,
      int experienceToNextLevel, int wood, int food, int enemyHealth, Status status,
      long revision, String message) {
    public Player {
      UUID.fromString(survivorId);
      if (accountId == null) UUID.fromString(token);
      else if (accountId.isBlank() || accountId.length() > 128 || token != null) {
        throw new IllegalArgumentException("Saved survivor ownership is invalid.");
      }
      if (name == null || name.isBlank() || name.length() > 32
          || name.codePoints().anyMatch(Character::isISOControl) || lastSeen == null
          || health < 0 || health > 10 || strength < 2 || strength > 20
          || stamina != strength + 8 || experience < 0 || experienceToNextLevel < 1
          || experienceToNextLevel != (1 << (strength - 2)) || experience >= experienceToNextLevel
          || wood < 0 || food < 0 || wood + food > 10 || enemyHealth < 0 || enemyHealth > 5
          || status == null || (status == Status.DEAD) != (health == 0)
          || (status == Status.COMBAT) != (enemyHealth > 0) || revision < 0
          || message == null || message.length() > 1024) {
        throw new IllegalArgumentException("Saved survivor progress is invalid.");
      }
    }
  }
}
