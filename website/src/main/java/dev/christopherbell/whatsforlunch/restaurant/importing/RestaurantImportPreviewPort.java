package dev.christopherbell.whatsforlunch.restaurant.importing;

import java.time.Instant;
import java.util.Optional;

/** Atomic persistence boundary for short-lived import preview authorization. */
public interface RestaurantImportPreviewPort {
  RestaurantImportPreviewDocument save(RestaurantImportPreviewDocument preview);

  Optional<RestaurantImportPreviewDocument> claim(String token, String actorAccountId, Instant now);
}
