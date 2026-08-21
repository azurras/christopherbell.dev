package dev.christopherbell.sharedfolder.radio;

import static dev.christopherbell.persistence.jooq.shared_folder.Tables.RADIO_STATE;
import static dev.christopherbell.persistence.jooq.shared_folder.Tables.RADIO_TRACK_DURATION;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.dao.OptimisticLockingFailureException;

/** PostgreSQL implementation of the one durable shared-folder radio station. */
@PostgresPersistence
public class PostgresSharedFolderRadioRepository implements SharedFolderRadioRepository {
  private final DSLContext database;

  public PostgresSharedFolderRadioRepository(DSLContext database) {
    this.database = database;
  }

  @Override public Optional<SharedFolderRadioDocument> findById(String id) {
    return find(database, id);
  }

  @Override public SharedFolderRadioDocument save(SharedFolderRadioDocument document) {
    return database.transactionResult(configuration -> {
      DSLContext transaction = DSL.using(configuration);
      String path = document.path() == null ? null
          : PostgresqlRelativePath.require(document.path(), "Shared-folder radio path");
      if (document.version() == null) {
        transaction.insertInto(RADIO_STATE).set(RADIO_STATE.RADIO_STATE_ID, document.id())
            .set(RADIO_STATE.STATE, document.state().name())
            .set(RADIO_STATE.STATION_SEQUENCE, document.stationSequence()).set(RADIO_STATE.RELATIVE_PATH, path)
            .set(RADIO_STATE.STARTED_AT,
                document.startedAt() == null ? null : document.startedAt().atOffset(ZoneOffset.UTC))
            .set(RADIO_STATE.DURATION_SECONDS,
                document.durationSeconds() == null ? null : BigDecimal.valueOf(document.durationSeconds()))
            .set(RADIO_STATE.VERSION, 0L).execute();
      } else {
        long nextVersion = Math.incrementExact(document.version());
        int changed = transaction.update(RADIO_STATE).set(RADIO_STATE.STATE, document.state().name())
            .set(RADIO_STATE.STATION_SEQUENCE, document.stationSequence()).set(RADIO_STATE.RELATIVE_PATH, path)
            .set(RADIO_STATE.STARTED_AT,
                document.startedAt() == null ? null : document.startedAt().atOffset(ZoneOffset.UTC))
            .set(RADIO_STATE.DURATION_SECONDS,
                document.durationSeconds() == null ? null : BigDecimal.valueOf(document.durationSeconds()))
            .set(RADIO_STATE.VERSION, nextVersion)
            .where(RADIO_STATE.RADIO_STATE_ID.eq(document.id())
                .and(RADIO_STATE.VERSION.eq(document.version()))).execute();
        if (changed != 1) {
          throw new OptimisticLockingFailureException("Shared-folder radio changed during save.");
        }
      }
      transaction.deleteFrom(RADIO_TRACK_DURATION)
          .where(RADIO_TRACK_DURATION.RADIO_STATE_ID.eq(document.id())).execute();
      for (int ordinal = 0; ordinal < document.knownDurations().size(); ordinal++) {
        var duration = document.knownDurations().get(ordinal);
        transaction.insertInto(RADIO_TRACK_DURATION)
            .set(RADIO_TRACK_DURATION.RADIO_STATE_ID, document.id())
            .set(RADIO_TRACK_DURATION.ORDINAL, ordinal)
            .set(RADIO_TRACK_DURATION.RELATIVE_PATH,
                PostgresqlRelativePath.require(duration.path(), "Shared-folder duration path"))
            .set(RADIO_TRACK_DURATION.OBSERVED_TOKEN, duration.observedToken())
            .set(RADIO_TRACK_DURATION.DURATION_SECONDS, BigDecimal.valueOf(duration.durationSeconds()))
            .execute();
      }
      return find(transaction, document.id()).orElseThrow();
    });
  }

  private static Optional<SharedFolderRadioDocument> find(DSLContext context, String id) {
    return context.selectFrom(RADIO_STATE).where(RADIO_STATE.RADIO_STATE_ID.eq(id))
        .fetchOptional(row -> {
          List<SharedFolderRadioDocument.TrackDuration> durations = context
              .selectFrom(RADIO_TRACK_DURATION)
              .where(RADIO_TRACK_DURATION.RADIO_STATE_ID.eq(id))
              .orderBy(RADIO_TRACK_DURATION.ORDINAL.asc())
              .fetch(value -> new SharedFolderRadioDocument.TrackDuration(value.getRelativePath(),
                  value.getObservedToken(), value.getDurationSeconds().doubleValue()));
          return new SharedFolderRadioDocument(row.getRadioStateId(),
              SharedFolderRadioDocument.State.valueOf(row.getState()), row.getStationSequence(),
              row.getRelativePath(), row.getStartedAt() == null ? null : row.getStartedAt().toInstant(),
              row.getDurationSeconds() == null ? null : row.getDurationSeconds().doubleValue(),
              durations, row.getVersion());
        });
  }
}
