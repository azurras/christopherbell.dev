package dev.christopherbell.vehicle;

import dev.christopherbell.vehicle.model.NhtsaVinImportState;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for NHTSA VIN enrichment throttling state.
 */
public interface NhtsaVinImportStateRepository extends MongoRepository<NhtsaVinImportState, String> {}
