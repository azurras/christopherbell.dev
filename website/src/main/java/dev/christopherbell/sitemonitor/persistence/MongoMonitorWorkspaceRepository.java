package dev.christopherbell.sitemonitor.persistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.sitemonitor.api.MonitorProblem;
import dev.christopherbell.sitemonitor.model.MonitorSchedule;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Stores pilot workspaces in ten fixed slots ({@code pilot-0} to {@code pilot-9}) under one
 * explicit additive runtime kind, preserving the historical cutover manifest.
 */
@Repository
@MongoPersistence
public class MongoMonitorWorkspaceRepository implements MonitorWorkspaceRepository {
  private static final int PILOT_SLOT_COUNT = 10;
  private static final String SCHEDULE_ID = "daily";
  private static final Duration SCHEDULED_CHECK_SPACING = Duration.ofSeconds(60);

  private final KindScopedMongoOperations<MonitorWorkspace> mongo;
  private final KindScopedMongoOperations<MonitorSchedule> schedule;

  public MongoMonitorWorkspaceRepository(DomainMongoOperationsFactory factory) {
    mongo = factory.forType(MonitorWorkspace.class);
    schedule = factory.forType(MonitorSchedule.class);
  }

  @Override
  public Optional<MonitorWorkspace> findByAccountId(String accountId) {
    return listAll().stream()
        .filter(workspace -> workspace.accountId().equals(accountId))
        .findFirst();
  }

  @Override
  public MonitorWorkspace save(MonitorWorkspace workspace) {
    if (workspace.id() == null) {
      return mongo.insert(new MonitorWorkspace(freePilotSlotId(), null, workspace.accountId(),
          UUID.randomUUID().toString(), workspace.sites()));
    }
    return mongo.findAndUpdate(expected(workspace), new Update().set("sites", workspace.sites()))
        .orElseThrow(() -> new OptimisticLockingFailureException("Monitor workspace changed."));
  }

  @Override
  public List<MonitorWorkspace> listAll() {
    return pilotSlotIds()
        .map(mongo::findById)
        .flatMap(Optional::stream)
        .toList();
  }

  @Override
  public long count() {
    return listAll().size();
  }

  @Override
  public boolean claimScheduledMinute(Instant now) {
    Optional<MonitorSchedule> previousClaim = schedule.findById(SCHEDULE_ID);
    if (previousClaim.isPresent() && previousClaim.get().nextCheckOn().isAfter(now)) {
      return false;
    }
    Long previousVersion = previousClaim.map(MonitorSchedule::version).orElse(null);
    schedule.save(new MonitorSchedule(SCHEDULE_ID, previousVersion, now.plus(SCHEDULED_CHECK_SPACING)));
    return true;
  }

  @Override
  public void delete(MonitorWorkspace expected) {
    if (mongo.remove(expected(expected)).getDeletedCount() != 1) {
      throw new OptimisticLockingFailureException("Monitor workspace changed.");
    }
  }

  private String freePilotSlotId() {
    Set<String> occupiedSlotIds = listAll().stream()
        .map(MonitorWorkspace::id)
        .collect(Collectors.toSet());
    return pilotSlotIds()
        .filter(slotId -> !occupiedSlotIds.contains(slotId))
        .findFirst()
        .orElseThrow(() -> new MonitorProblem(409, "The pilot is currently full."));
  }

  private static Stream<String> pilotSlotIds() {
    return IntStream.range(0, PILOT_SLOT_COUNT).mapToObj(slot -> "pilot-" + slot);
  }

  /** Matches the stored workspace only at the exact id, owner, generation and version. */
  private static Query expected(MonitorWorkspace workspace) {
    if (workspace.id() == null || workspace.version() == null || workspace.generation() == null) {
      throw new OptimisticLockingFailureException("Monitor workspace identity is missing.");
    }
    return Query.query(Criteria.where("id").is(workspace.id())
        .and("accountId").is(workspace.accountId())
        .and("generation").is(workspace.generation())
        .and("version").is(workspace.version()));
  }
}
