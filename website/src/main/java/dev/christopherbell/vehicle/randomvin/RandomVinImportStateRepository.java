package dev.christopherbell.vehicle.randomvin;

import dev.christopherbell.vehicle.randomvin.model.RandomVinImportState;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for RandomVIN import throttling state.
 */
public interface RandomVinImportStateRepository extends MongoRepository<RandomVinImportState, String> {}
