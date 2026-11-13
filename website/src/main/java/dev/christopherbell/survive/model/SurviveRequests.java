package dev.christopherbell.survive.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request contracts accept commands and identity labels, never client-supplied game state. */
public final class SurviveRequests {
  private SurviveRequests() {}

  public record Join(@NotBlank @Size(max = 32) String name) {}
  public record Act(@NotNull SurviveAction action, @NotNull @Min(0) Long revision, @Size(max = 48) String targetId) {}
  public record Gift(@NotBlank @Size(max = 36) String recipientId, @NotNull SurviveResource resource,
      @NotNull @Min(1) @Max(10) Integer quantity, @NotNull @Min(0) Long revision) {}
  public record TradeOffer(@NotNull UUID recipientId, SurviveResource offered,
      @NotNull @Min(1) @Max(30) Integer offeredQuantity, @NotNull SurviveResource requested,
      @NotNull @Min(1) @Max(30) Integer requestedQuantity, @NotNull @Min(0) Long revision, UUID offeredWeaponId) {
    public TradeOffer(UUID recipientId, SurviveResource offered, Integer offeredQuantity, SurviveResource requested,
        Integer requestedQuantity, Long revision) {
      this(recipientId, offered, offeredQuantity, requested, requestedQuantity, revision, null);
    }
  }
  public record TradeReply(@NotNull UUID offerId, @NotNull Boolean accept, @NotNull @Min(0) Long revision) {}
  public record Party(@NotNull SurviveParty.Command command, @NotNull UUID targetId) {}
}
