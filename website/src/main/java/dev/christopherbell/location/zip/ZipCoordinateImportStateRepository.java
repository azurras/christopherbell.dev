package dev.christopherbell.location.zip;

import dev.christopherbell.location.model.ZipCoordinateImportState;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Stores observable ZIP coordinate import state by source dataset. */
public interface ZipCoordinateImportStateRepository
    extends MongoRepository<ZipCoordinateImportState, String> {}
