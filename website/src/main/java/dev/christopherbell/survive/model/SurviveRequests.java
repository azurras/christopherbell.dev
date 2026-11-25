package dev.christopherbell.survive.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;

/** Request contracts accept commands and identity labels, never client-supplied game state. */
public final class SurviveRequests {
  private SurviveRequests() {}

  public record Join(@NotBlank @Size(max = 32) String name) {}
  public record Act(@NotNull SurviveAction action, @NotNull @Min(0) Long revision, @Size(max = 48) String targetId) {}
  public record Gift(@NotBlank @Size(max = 36) String recipientId, @NotNull SurviveResource resource,
      @NotNull @Min(1) @Max(10) Integer quantity, @NotNull @Min(0) Long revision) {}
  public record TradeOffer(@NotNull UUID recipientId, SurviveResource offered,
      @NotNull @Min(1) @Max(30) Integer offeredQuantity, SurviveResource requested,
      @Min(1) @Max(30) Integer requestedQuantity, @NotNull @Min(0) Long revision, UUID offeredWeaponId,
      UUID counterOfferId, @Size(max = SurviveTrade.MAX_LOTS - 1) List<@NotNull @Valid TradeItem> additionalOffered) {
    public TradeOffer(UUID recipientId, SurviveResource offered, Integer offeredQuantity, SurviveResource requested,
        Integer requestedQuantity, Long revision, UUID offeredWeaponId, UUID counterOfferId) {
      this(recipientId, offered, offeredQuantity, requested, requestedQuantity, revision, offeredWeaponId,
          counterOfferId, List.of());
    }
    public TradeOffer(UUID recipientId, SurviveResource offered, Integer offeredQuantity, SurviveResource requested,
        Integer requestedQuantity, Long revision, UUID offeredWeaponId) {
      this(recipientId, offered, offeredQuantity, requested, requestedQuantity, revision, offeredWeaponId, null);
    }
    public TradeOffer(UUID recipientId, SurviveResource offered, Integer offeredQuantity, SurviveResource requested,
        Integer requestedQuantity, Long revision) {
      this(recipientId, offered, offeredQuantity, requested, requestedQuantity, revision, null);
    }
  }
  public record TradeItem(SurviveResource resource, @NotNull @Min(1) @Max(30) Integer quantity, UUID weaponId) {}
  public record TradeReply(@NotNull UUID offerId, @NotNull Boolean accept, @NotNull @Min(0) Long revision) {}
  public record Party(@NotNull SurviveParty.Command command, @NotNull UUID targetId) {}
}
