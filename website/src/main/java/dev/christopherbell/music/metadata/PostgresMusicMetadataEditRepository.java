package dev.christopherbell.music.metadata;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL implementation of private Music metadata-edit records. */
@PostgresPersistence
public class PostgresMusicMetadataEditRepository implements MusicMetadataEditRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresMusicMetadataEditRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("music", "metadata_edit");
  }

  @Override
  public MusicMetadataEdit save(MusicMetadataEdit edit) {
    String sourcePath =
        PostgresqlRelativePath.require(edit.sourcePath(), "Music metadata source path");
    if (edit.version() == null) {
      database.sql("""
              insert into %s (
                metadata_edit_id, track_id, source_path, backup_file_name, backup_sha256,
                original_observed_token, replacement_observed_token, original_audio_codec,
                original_duration_seconds, edited_by_account_id, created_at, expires_at,
                status, undone_at, version)
              values (
                :id, :trackId, :sourcePath, :backupFileName, :backupSha256,
                :originalToken, :replacementToken, :codec, :duration,
                :accountId, :createdAt, :expiresAt, :status, :undoneAt, 0)
              """.formatted(table))
          .paramSource(parameters(edit, sourcePath))
          .update();
    } else {
      long nextVersion = Math.incrementExact(edit.version());
      int changed = database.sql("""
              update %s set
                track_id = :trackId, source_path = :sourcePath,
                backup_file_name = :backupFileName, backup_sha256 = :backupSha256,
                original_observed_token = :originalToken,
                replacement_observed_token = :replacementToken,
                original_audio_codec = :codec, original_duration_seconds = :duration,
                edited_by_account_id = :accountId, created_at = :createdAt,
                expires_at = :expiresAt, status = :status, undone_at = :undoneAt,
                version = :nextVersion
              where metadata_edit_id = :id and version = :expectedVersion
              """.formatted(table))
          .paramSource(parameters(edit, sourcePath)
              .addValue("nextVersion", nextVersion)
              .addValue("expectedVersion", edit.version()))
          .update();
      if (changed != 1) {
        throw new OptimisticLockingFailureException("Music metadata edit changed during save.");
      }
    }
    return findById(edit.id()).orElseThrow();
  }

  @Override
  public Optional<MusicMetadataEdit> findById(String id) {
    return database.sql("select * from %s where metadata_edit_id = :id".formatted(table))
        .param("id", id).query(PostgresMusicMetadataEditRepository::map).optional();
  }

  @Override
  public void deleteById(String id) {
    database.sql("delete from %s where metadata_edit_id = :id".formatted(table))
        .param("id", id).update();
  }

  @Override
  public void delete(MusicMetadataEdit edit) {
    var statement = edit.version() == null
        ? database.sql("delete from %s where metadata_edit_id = :id".formatted(table))
            .param("id", edit.id())
        : database.sql("delete from %s where metadata_edit_id = :id and version = :version"
                .formatted(table))
            .param("id", edit.id()).param("version", edit.version());
    if (statement.update() != 1) {
      throw new OptimisticLockingFailureException("Music metadata edit changed during deletion.");
    }
  }

  @Override
  public List<MusicMetadataEdit> findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(Instant cutoff) {
    return database.sql("""
            select * from %s where expires_at < :cutoff
            order by expires_at asc, metadata_edit_id asc limit 100
            """.formatted(table))
        .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
        .query(PostgresMusicMetadataEditRepository::map).list();
  }

  private static MapSqlParameterSource parameters(MusicMetadataEdit edit, String sourcePath) {
    return new MapSqlParameterSource()
        .addValue("id", edit.id()).addValue("trackId", edit.trackId())
        .addValue("sourcePath", sourcePath).addValue("backupFileName", edit.backupFileName())
        .addValue("backupSha256", edit.backupSha256())
        .addValue("originalToken", edit.originalObservedToken())
        .addValue("replacementToken", edit.replacementObservedToken(), Types.VARCHAR)
        .addValue("codec", edit.originalAudioCodec(), Types.VARCHAR)
        .addValue("duration", BigDecimal.valueOf(edit.originalDurationSeconds()))
        .addValue("accountId", edit.editedByAccountId())
        .addValue("createdAt", edit.createdAt().atOffset(ZoneOffset.UTC))
        .addValue("expiresAt", edit.expiresAt().atOffset(ZoneOffset.UTC))
        .addValue("status", edit.status().name())
        .addValue("undoneAt", offset(edit.undoneAt()), Types.TIMESTAMP_WITH_TIMEZONE);
  }

  private static MusicMetadataEdit map(ResultSet row, int rowNumber) throws SQLException {
    return new MusicMetadataEdit(
        row.getString("metadata_edit_id"), row.getString("track_id"),
        row.getString("source_path"), row.getString("backup_file_name"),
        row.getString("backup_sha256"), row.getString("original_observed_token"),
        row.getString("replacement_observed_token"), row.getString("original_audio_codec"),
        row.getBigDecimal("original_duration_seconds").doubleValue(),
        row.getString("edited_by_account_id"),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
        MusicMetadataEdit.Status.valueOf(row.getString("status")),
        instant(row.getObject("undone_at", OffsetDateTime.class)), row.getLong("version"));
  }

  private static OffsetDateTime offset(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
