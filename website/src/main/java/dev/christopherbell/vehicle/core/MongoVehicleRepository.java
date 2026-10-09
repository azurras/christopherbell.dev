package dev.christopherbell.vehicle.core;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.vehicle.model.Vehicle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@MongoPersistence
@Repository
public class MongoVehicleRepository extends KindScopedRepositorySupport<Vehicle>
    implements VehicleRepository {
  public MongoVehicleRepository(DomainMongoOperationsFactory factory) {
    super(factory, Vehicle.class);
  }

  @Override
  public Vehicle save(Vehicle vehicle) {
    return saveValue(vehicle);
  }

  @Override
  public List<Vehicle> saveAll(Iterable<Vehicle> vehicles) {
    var saved = new ArrayList<Vehicle>();
    vehicles.forEach(vehicle -> saved.add(saveValue(vehicle)));
    return List.copyOf(saved);
  }

  @Override
  public Optional<Vehicle> findById(String id) {
    return findValueById(id);
  }

  @Override
  public void delete(Vehicle vehicle) {
    deleteById(vehicle.getId());
  }

  @Override
  public boolean existsByVin(String vin) {
    return mongo.exists(Query.query(Criteria.where("vin").is(vin)));
  }

  @Override
  public List<Vehicle> findByNotes(String notes) {
    return find(Query.query(Criteria.where("notes").is(notes)));
  }

  @Override
  public List<Vehicle> findByVinIsNotNull() {
    return find(Query.query(Criteria.where("vin").ne(null)));
  }

  @Override
  public List<Vehicle> findByMakeIgnoreCase(String make) {
    var exactMake = Pattern.compile("^" + Pattern.quote(make) + "$", Pattern.CASE_INSENSITIVE);
    return find(Query.query(Criteria.where("make").regex(exactMake)));
  }

  @Override
  public List<Vehicle> findAllByOrderByMakeAscModelAscYearDesc() {
    return find(new Query().with(Sort.by(
        Sort.Order.asc("make"), Sort.Order.asc("model"), Sort.Order.desc("year"))));
  }
}
