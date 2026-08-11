package dev.christopherbell.location.zip;

import dev.christopherbell.location.model.ZipCoordinateImportState;
import java.util.Optional;

/** Stores observable ZIP coordinate import state by source dataset. */
public interface ZipCoordinateImportStateRepository {
  Optional<ZipCoordinateImportState> findById(String id);
  ZipCoordinateImportState save(ZipCoordinateImportState state);
}
