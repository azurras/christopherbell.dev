package dev.christopherbell.music.metadata;

import static dev.christopherbell.persistence.jooq.music.Tables.METADATA_EDIT;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import dev.christopherbell.persistence.jooq.music.tables.records.MetadataEditRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.dao.OptimisticLockingFailureException;

/** PostgreSQL implementation of private Music metadata-edit records. */
@PostgresPersistence
public class PostgresMusicMetadataEditRepository implements MusicMetadataEditRepository {
  private final DSLContext database;

  public PostgresMusicMetadataEditRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public MusicMetadataEdit save(MusicMetadataEdit edit) {
    String sourcePath = PostgresqlRelativePath.require(edit.sourcePath(), "Music metadata source path");
    if (edit.version() == null) {
      database.insertInto(METADATA_EDIT).set(METADATA_EDIT.METADATA_EDIT_ID, edit.id())
          .set(METADATA_EDIT.TRACK_ID, edit.trackId()).set(METADATA_EDIT.SOURCE_PATH, sourcePath)
          .set(METADATA_EDIT.BACKUP_FILE_NAME, edit.backupFileName())
          .set(METADATA_EDIT.BACKUP_SHA256, edit.backupSha256())
          .set(METADATA_EDIT.ORIGINAL_OBSERVED_TOKEN, edit.originalObservedToken())
          .set(METADATA_EDIT.REPLACEMENT_OBSERVED_TOKEN, edit.replacementObservedToken())
          .set(METADATA_EDIT.ORIGINAL_AUDIO_CODEC, edit.originalAudioCodec())
          .set(METADATA_EDIT.ORIGINAL_DURATION_SECONDS, BigDecimal.valueOf(edit.originalDurationSeconds()))
          .set(METADATA_EDIT.EDITED_BY_ACCOUNT_ID, edit.editedByAccountId())
          .set(METADATA_EDIT.CREATED_AT, edit.createdAt().atOffset(ZoneOffset.UTC))
          .set(METADATA_EDIT.EXPIRES_AT, edit.expiresAt().atOffset(ZoneOffset.UTC))
          .set(METADATA_EDIT.STATUS, edit.status().name()).set(METADATA_EDIT.UNDONE_AT, offset(edit.undoneAt()))
          .set(METADATA_EDIT.VERSION, 0L).execute();
    } else {
      long nextVersion = Math.incrementExact(edit.version());
      int changed = database.update(METADATA_EDIT).set(METADATA_EDIT.TRACK_ID, edit.trackId())
          .set(METADATA_EDIT.SOURCE_PATH, sourcePath).set(METADATA_EDIT.BACKUP_FILE_NAME, edit.backupFileName())
          .set(METADATA_EDIT.BACKUP_SHA256, edit.backupSha256())
          .set(METADATA_EDIT.ORIGINAL_OBSERVED_TOKEN, edit.originalObservedToken())
          .set(METADATA_EDIT.REPLACEMENT_OBSERVED_TOKEN, edit.replacementObservedToken())
          .set(METADATA_EDIT.ORIGINAL_AUDIO_CODEC, edit.originalAudioCodec())
          .set(METADATA_EDIT.ORIGINAL_DURATION_SECONDS, BigDecimal.valueOf(edit.originalDurationSeconds()))
          .set(METADATA_EDIT.EDITED_BY_ACCOUNT_ID, edit.editedByAccountId())
          .set(METADATA_EDIT.CREATED_AT, edit.createdAt().atOffset(ZoneOffset.UTC))
          .set(METADATA_EDIT.EXPIRES_AT, edit.expiresAt().atOffset(ZoneOffset.UTC))
          .set(METADATA_EDIT.STATUS, edit.status().name()).set(METADATA_EDIT.UNDONE_AT, offset(edit.undoneAt()))
          .set(METADATA_EDIT.VERSION, nextVersion)
          .where(METADATA_EDIT.METADATA_EDIT_ID.eq(edit.id()).and(METADATA_EDIT.VERSION.eq(edit.version())))
          .execute();
      if (changed != 1) {
        throw new OptimisticLockingFailureException("Music metadata edit changed during save.");
      }
    }
    return findById(edit.id()).orElseThrow();
  }

  @Override public Optional<MusicMetadataEdit> findById(String id) {
    return database.selectFrom(METADATA_EDIT).where(METADATA_EDIT.METADATA_EDIT_ID.eq(id))
        .fetchOptional(PostgresMusicMetadataEditRepository::map);
  }

  @Override public void deleteById(String id) {
    database.deleteFrom(METADATA_EDIT).where(METADATA_EDIT.METADATA_EDIT_ID.eq(id)).execute();
  }

  @Override public void delete(MusicMetadataEdit edit) {
    var condition = METADATA_EDIT.METADATA_EDIT_ID.eq(edit.id());
    if (edit.version() != null) condition = condition.and(METADATA_EDIT.VERSION.eq(edit.version()));
    if (database.deleteFrom(METADATA_EDIT).where(condition).execute() != 1) {
      throw new OptimisticLockingFailureException("Music metadata edit changed during deletion.");
    }
  }

  @Override public List<MusicMetadataEdit> findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(Instant cutoff) {
    return database.selectFrom(METADATA_EDIT)
        .where(METADATA_EDIT.EXPIRES_AT.lt(cutoff.atOffset(ZoneOffset.UTC)))
        .orderBy(METADATA_EDIT.EXPIRES_AT.asc(), METADATA_EDIT.METADATA_EDIT_ID.asc()).limit(100)
        .fetch(PostgresMusicMetadataEditRepository::map);
  }

  private static MusicMetadataEdit map(MetadataEditRecord row) {
    return new MusicMetadataEdit(row.getMetadataEditId(), row.getTrackId(), row.getSourcePath(),
        row.getBackupFileName(), row.getBackupSha256(), row.getOriginalObservedToken(),
        row.getReplacementObservedToken(), row.getOriginalAudioCodec(),
        row.getOriginalDurationSeconds().doubleValue(), row.getEditedByAccountId(),
        row.getCreatedAt().toInstant(), row.getExpiresAt().toInstant(),
        MusicMetadataEdit.Status.valueOf(row.getStatus()), instant(row.getUndoneAt()), row.getVersion());
  }

  private static java.time.OffsetDateTime offset(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(java.time.OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
