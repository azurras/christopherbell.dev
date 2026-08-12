package dev.christopherbell.vehicle.nhtsa.enrichment;

import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import java.util.Optional;

/**
 * Repository for NHTSA VIN enrichment throttling state.
 */
public interface NhtsaVinImportStateRepository {
  Optional<NhtsaVinImportState> findById(String id);
  NhtsaVinImportState save(NhtsaVinImportState state);
}
