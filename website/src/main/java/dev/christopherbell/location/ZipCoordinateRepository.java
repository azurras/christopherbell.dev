package dev.christopherbell.location;

import dev.christopherbell.location.model.ZipCoordinate;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Mongo repository for general Location ZIP coordinate data.
 */
public interface ZipCoordinateRepository extends MongoRepository<ZipCoordinate, String> {
  List<ZipCoordinate> findAllBySource(String source);
}
