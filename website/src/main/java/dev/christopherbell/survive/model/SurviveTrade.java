package dev.christopherbell.survive.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/** Immutable terms that the recipient must accept before either pack changes. */
public record SurviveTrade(String id, String senderId, String recipientId, Lot offered, Lot requested, Instant expiresAt,
    List<Lot> additionalOffered, List<Lot> additionalRequested) {
  public static final int MAX_LOTS = 8;

  public SurviveTrade(String id, String senderId, String recipientId, Lot offered, Lot requested, Instant expiresAt) {
    this(id, senderId, recipientId, offered, requested, expiresAt, List.of(), List.of());
  }

  public SurviveTrade {
    UUID.fromString(id);
    UUID.fromString(senderId);
    UUID.fromString(recipientId);
    Objects.requireNonNull(offered);
    Objects.requireNonNull(requested);
    Objects.requireNonNull(expiresAt);
    additionalOffered = additionalOffered == null ? List.of() : List.copyOf(additionalOffered);
    additionalRequested = additionalRequested == null ? List.of() : List.copyOf(additionalRequested);
    if (senderId.equals(recipientId)) {
      throw new IllegalArgumentException("A trade requires two survivors.");
    }
    validateLots(offered, additionalOffered);
    validateLots(requested, additionalRequested);
  }

  private static void validateLots(Lot first, List<Lot> additional) {
    var keys = new HashSet<String>();
    if (additional.size() >= MAX_LOTS
        || Stream.concat(Stream.of(first), additional.stream()).anyMatch(lot -> !keys.add(lot.itemKey()))) {
      throw new IllegalArgumentException("Trade lots must be unique and within the offer limit.");
    }
  }

  public List<Lot> offeredLots() {
    return Stream.concat(Stream.of(offered), additionalOffered.stream()).toList();
  }

  public List<Lot> requestedLots() {
    return Stream.concat(Stream.of(requested), additionalRequested.stream()).toList();
  }

  public static String describe(List<Lot> lots) {
    return String.join(", ", lots.stream().map(Lot::description).toList());
  }

  /** One supply stack or one exact packed weapon, including its condition. */
  public record Lot(SurviveResource resource, int quantity, SurvivePackedWeapon packedWeapon) {
    public Lot(SurviveResource resource, int quantity) {
      this(resource, quantity, null);
    }

    public Lot {
      if ((resource == null) == (packedWeapon == null) || packedWeapon != null && quantity != 1) {
        throw new IllegalArgumentException("A trade lot must contain supplies or a single packed weapon.");
      }
      if (quantity < 1 || quantity > 30) {
        throw new IllegalArgumentException("Trade quantities must be between 1 and 30.");
      }
    }

    public String itemKey() {
      return packedWeapon == null ? resource.name() : packedWeapon.id();
    }

    @JsonProperty("description")
    public String description() {
      if (packedWeapon != null) {
        return packedWeapon.weapon().displayName() + " (" + packedWeapon.durability() + " uses left)";
      }
      return quantity + " " + resource.name().toLowerCase(Locale.ROOT);
    }
  }

  public boolean involves(String survivorId) {
    return senderId.equals(survivorId) || recipientId.equals(survivorId);
  }

  /** Private view of an offer and the name of the other participant. */
  public record View(SurviveTrade offer, String partner, boolean incoming) {}
}
