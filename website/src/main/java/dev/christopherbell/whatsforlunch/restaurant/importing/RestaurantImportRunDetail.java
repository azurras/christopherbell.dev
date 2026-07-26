package dev.christopherbell.whatsforlunch.restaurant.importing;

import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportResult;
import java.time.Instant;

/** Operator-visible outcome of one import attempt. */
public record RestaurantImportRunDetail(
    RestaurantImportRunStatus status,
    String trigger,
    Instant startedOn,
    Instant endedOn,
    RestaurantImportResult result,
    String errorCategory
) {}
