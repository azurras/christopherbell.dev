package dev.christopherbell.configuration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.music.security.MusicAccessAttemptRepository;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MediaPersistenceRetentionCleanupJobTest {
  @Test
  void oneScheduledBatchReportsBothBoundedCleanupCounts() {
    var accesses = mock(MusicAccessAttemptRepository.class);
    var audits = mock(SharedFolderAuditRepository.class);
    Instant cutoff = Instant.parse("2026-08-14T00:00:00Z");
    when(accesses.deleteExpired(cutoff, 7)).thenReturn(2);
    when(audits.deleteExpired(cutoff, 7)).thenReturn(3);
    var job = new MediaPersistenceRetentionCleanupJob(
        accesses, audits, Clock.fixed(cutoff, ZoneOffset.UTC), 7);

    assertThat(job.cleanup()).isEqualTo(new MediaPersistenceCleanupResult(2, 3));
    verify(accesses).deleteExpired(cutoff, 7);
    verify(audits).deleteExpired(cutoff, 7);
  }
}
