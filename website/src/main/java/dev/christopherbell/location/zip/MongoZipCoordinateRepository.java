package dev.christopherbell.location.zip;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.location.model.ZipCoordinate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public class MongoZipCoordinateRepository extends KindScopedRepositorySupport<ZipCoordinate>
    implements ZipCoordinateRepository {
  public MongoZipCoordinateRepository(DomainMongoOperationsFactory factory) { super(factory, ZipCoordinate.class); }
  @Override public List<ZipCoordinate> saveAll(Iterable<ZipCoordinate> values) {
    var saved = new ArrayList<ZipCoordinate>();
    values.forEach(value -> saved.add(saveValue(value)));
    return List.copyOf(saved);
  }
  @Override public void deleteAll(Iterable<ZipCoordinate> values) { deleteAllValues(values, ZipCoordinate::getZipCode); }
  @Override public Optional<ZipCoordinate> findById(String id) { return findValueById(id); }
  @Override public List<ZipCoordinate> findAllBySource(String source) {
    return find(Query.query(Criteria.where("source").is(source)));
  }
}
