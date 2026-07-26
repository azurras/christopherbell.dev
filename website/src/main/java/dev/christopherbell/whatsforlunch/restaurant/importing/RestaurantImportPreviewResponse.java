package dev.christopherbell.whatsforlunch.restaurant.importing;

import java.time.Instant;
import java.util.List;

/** Operator-facing import preview. */
public record RestaurantImportPreviewResponse(
    String token,
    String checksum,
    Instant expiresOn,
    RestaurantImportPreviewCounts counts,
    List<String> representativeChanges
) {
  public RestaurantImportPreviewResponse {
    representativeChanges = List.copyOf(representativeChanges);
  }
}
