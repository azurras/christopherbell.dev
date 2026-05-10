package dev.christopherbell.vehicle;

import dev.christopherbell.vehicle.model.RandomVinImportState;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for RandomVIN import throttling state.
 */
public interface RandomVinImportStateRepository extends MongoRepository<RandomVinImportState, String> {}
