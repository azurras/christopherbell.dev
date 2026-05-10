package dev.christopherbell.vehicle.nhtsa;

import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for NHTSA VIN enrichment throttling state.
 */
public interface NhtsaVinImportStateRepository extends MongoRepository<NhtsaVinImportState, String> {}
