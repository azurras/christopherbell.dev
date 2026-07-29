package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.SharedFolderMediaProperties;
import dev.christopherbell.configuration.SharedFolderProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.util.unit.DataSize;

@ExtendWith(MockitoExtension.class)
class V012RetainSharedFolderWorkTest {
  private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");
  @Mock private MongoTemplate mongo;
  @Mock private IndexOperations uploadIndexes;
  @Mock private IndexOperations mediaIndexes;

  @Test
  void backfillsOnlyCompletedAndTerminalWorkAndCreatesCleanupIndexes() {
    when(mongo.indexOps("shared_folder_upload_sessions")).thenReturn(uploadIndexes);
    when(mongo.indexOps("shared_folder_media_jobs")).thenReturn(mediaIndexes);
    when(mongo.find(any(Query.class), eq(Document.class),
        eq("shared_folder_upload_sessions"))).thenReturn(
            List.of(new Document("_id", "upload-1").append("state", "COMPLETED")
                .append("updatedAt", NOW)), List.of());
    when(mongo.find(any(Query.class), eq(Document.class),
        eq("shared_folder_media_jobs"))).thenReturn(
            List.of(new Document("_id", "media-1").append("status", "FAILED")
                .append("updatedAt", NOW)), List.of());

    V012RetainSharedFolderWork migration = new V012RetainSharedFolderWork(
        folders(), media(), Clock.fixed(NOW, ZoneOffset.UTC));
    migration.apply(mongo);

    assertThat(migration.id()).isEqualTo("012-retain-shared-folder-work");
    assertThat(migration.checksum()).hasSize(64);
    verify(uploadIndexes).createIndex(any());
    verify(mediaIndexes, times(2)).createIndex(any());
    ArgumentCaptor<UpdateDefinition> upload = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).updateFirst(any(Query.class), upload.capture(),
        eq("shared_folder_upload_sessions"));
    assertThat(upload.getValue().getUpdateObject().toString())
        .contains("deleteAt=2026-08-05T12:00:00Z");
    ArgumentCaptor<UpdateDefinition> mediaUpdate = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).updateFirst(any(Query.class), mediaUpdate.capture(),
        eq("shared_folder_media_jobs"));
    assertThat(mediaUpdate.getValue().getUpdateObject().toString())
        .contains("cleanupAfter=2026-07-29T13:00:00Z", "artifactsCleaned=false", "$unset");
    verify(mongo, times(2)).updateFirst(any(Query.class), any(UpdateDefinition.class),
        eq("shared_folder_radio"));
  }

  private SharedFolderProperties folders() {
    return new SharedFolderProperties(
        Path.of("shared"), Path.of("system"), DataSize.ofGigabytes(1), DataSize.ofMegabytes(1),
        DataSize.ofMegabytes(1), DataSize.ofGigabytes(1), Duration.ofDays(30),
        Duration.ofDays(180), Duration.ofDays(7), true);
  }

  private SharedFolderMediaProperties media() {
    return new SharedFolderMediaProperties(
        4, 2, Duration.ofHours(1), Duration.ofMillis(10), Duration.ofSeconds(1),
        DataSize.ofMegabytes(1), DataSize.ofGigabytes(1), Duration.ofHours(1),
        Duration.ofMinutes(15), Duration.ofHours(2), Duration.ofHours(3), Duration.ofDays(7));
  }
}
