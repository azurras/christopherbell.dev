package dev.christopherbell.vehicle.randomvin.importing;

import dev.christopherbell.vehicle.randomvin.model.RandomVinImportState;
import java.util.Optional;

/**
 * Repository for RandomVIN import throttling state.
 */
public interface RandomVinImportStateRepository {
  Optional<RandomVinImportState> findById(String id);
  RandomVinImportState save(RandomVinImportState state);
}
