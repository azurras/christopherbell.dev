package dev.christopherbell.location.zip;

import dev.christopherbell.location.model.ZipCoordinate;
import java.util.List;
import java.util.Optional;

/**
 * Mongo repository for general Location ZIP coordinate data.
 */
public interface ZipCoordinateRepository {
  List<ZipCoordinate> saveAll(Iterable<ZipCoordinate> coordinates);
  void deleteAll(Iterable<ZipCoordinate> coordinates);
  Optional<ZipCoordinate> findById(String id);
  List<ZipCoordinate> findAllBySource(String source);
}
