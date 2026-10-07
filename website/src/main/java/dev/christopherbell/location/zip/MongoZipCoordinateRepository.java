package dev.christopherbell.location.zip;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.location.model.ZipCoordinate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Stores ZIP coordinates in the location domain collection. */
@MongoPersistence
@Repository
public class MongoZipCoordinateRepository extends KindScopedRepositorySupport<ZipCoordinate>
    implements ZipCoordinateRepository {

  public MongoZipCoordinateRepository(DomainMongoOperationsFactory factory) {
    super(factory, ZipCoordinate.class);
  }

  @Override
  public List<ZipCoordinate> saveAll(Iterable<ZipCoordinate> coordinates) {
    List<ZipCoordinate> savedCoordinates = new ArrayList<>();
    for (ZipCoordinate coordinate : coordinates) {
      savedCoordinates.add(saveValue(coordinate));
    }
    return List.copyOf(savedCoordinates);
  }

  @Override
  public void deleteAll(Iterable<ZipCoordinate> coordinates) {
    List<String> zipCodes = new ArrayList<>();
    for (ZipCoordinate coordinate : coordinates) {
      zipCodes.add(coordinate.getZipCode());
    }
    if (!zipCodes.isEmpty()) {
      mongo.remove(Query.query(Criteria.where("zipCode").in(zipCodes)));
    }
  }

  @Override
  public Optional<ZipCoordinate> findById(String zipCode) {
    return findValueById(zipCode);
  }

  @Override
  public List<ZipCoordinate> findAllBySource(String source) {
    return find(Query.query(Criteria.where("source").is(source)));
  }
}
