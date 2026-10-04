package dev.christopherbell.sitemonitor.persistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace;
import dev.christopherbell.sitemonitor.model.MonitorSchedule;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

/** Uses one explicit additive runtime kind, preserving the historical cutover manifest. */
@Repository
@MongoPersistence
public class MongoMonitorWorkspaceRepository implements MonitorWorkspaceRepository {
  private final KindScopedMongoOperations<MonitorWorkspace> mongo;
  private final KindScopedMongoOperations<MonitorSchedule> schedule;
  public MongoMonitorWorkspaceRepository(DomainMongoOperationsFactory factory) {
    mongo = factory.forType(MonitorWorkspace.class);
    schedule = factory.forType(MonitorSchedule.class);
  }
  public Optional<MonitorWorkspace> find(String accountId) {
    return list().stream().filter(workspace -> workspace.accountId().equals(accountId)).findFirst();
  }
  public MonitorWorkspace save(MonitorWorkspace workspace) {
    if (workspace.id() == null) {
      var occupied = list().stream().map(MonitorWorkspace::id).collect(java.util.stream.Collectors.toSet());
      String id = java.util.stream.IntStream.range(0, 10).mapToObj(slot -> "pilot-" + slot)
          .filter(slot -> !occupied.contains(slot)).findFirst()
          .orElseThrow(() -> new dev.christopherbell.sitemonitor.api.MonitorProblem(409, "The pilot is currently full."));
      var assigned = new MonitorWorkspace(id, null, workspace.accountId(),
          java.util.UUID.randomUUID().toString(), workspace.sites());
      return mongo.insert(assigned);
    }
    return mongo.findAndUpdate(expected(workspace), new Update().set("sites", workspace.sites()))
        .orElseThrow(() -> new OptimisticLockingFailureException("Monitor workspace changed."));
  }
  public List<MonitorWorkspace> list() {
    return java.util.stream.IntStream.range(0, 10).mapToObj(slot -> mongo.findById("pilot-" + slot))
        .flatMap(Optional::stream).toList();
  }
  public long count() { return list().size(); }
  public boolean claimScheduledMinute(java.time.Instant now) {
    var previous = schedule.findById("daily");
    if (previous.isPresent() && previous.get().nextCheckOn().isAfter(now)) return false;
    schedule.save(new MonitorSchedule("daily", previous.map(MonitorSchedule::version).orElse(null),
        now.plusSeconds(60)));
    return true;
  }
  public void delete(MonitorWorkspace workspace) {
    if (mongo.remove(expected(workspace)).getDeletedCount() != 1) {
      throw new OptimisticLockingFailureException("Monitor workspace changed.");
    }
  }
  private static Query expected(MonitorWorkspace workspace) {
    if (workspace.id() == null || workspace.version() == null || workspace.generation() == null) {
      throw new OptimisticLockingFailureException("Monitor workspace identity is missing.");
    }
    return Query.query(Criteria.where("id").is(workspace.id()).and("accountId").is(workspace.accountId())
        .and("generation").is(workspace.generation()).and("version").is(workspace.version()));
  }
}
