package dev.christopherbell.survive.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Immutable terms that the recipient must accept before either pack changes. */
public record SurviveTrade(String id, String senderId, String recipientId, Lot offered, Lot requested, Instant expiresAt) {
  public SurviveTrade {
    UUID.fromString(id);
    UUID.fromString(senderId);
    UUID.fromString(recipientId);
    Objects.requireNonNull(offered);
    Objects.requireNonNull(requested);
    Objects.requireNonNull(expiresAt);
    if (senderId.equals(recipientId) || offered.resource() == requested.resource()) {
      throw new IllegalArgumentException("A trade requires two survivors and different supplies.");
    }
  }

  /** One stack of supplies in the exchange. */
  public record Lot(SurviveResource resource, int quantity) {
    public Lot {
      Objects.requireNonNull(resource);
      if (quantity < 1 || quantity > 30) {
        throw new IllegalArgumentException("Trade quantities must be between 1 and 30.");
      }
    }

    public String description() {
      return quantity + " " + resource.name().toLowerCase(Locale.ROOT);
    }
  }

  public boolean involves(String survivorId) {
    return senderId.equals(survivorId) || recipientId.equals(survivorId);
  }

  /** Private view of an offer and the name of the other participant. */
  public record View(SurviveTrade offer, String partner, boolean incoming) {}
}
