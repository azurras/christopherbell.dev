package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.audit.MongoSharedFolderAuditSink;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditCommand;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditEvent;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.util.unit.DataSize;

class SharedFolderAuditPersistenceTest {
  @Test
  void mongoSinkPersistsOnlyTheBoundedCommandShapeWithConfiguredExpiry() {
    SharedFolderAuditRepository repository = mock(SharedFolderAuditRepository.class);
    SharedFolderProperties properties = properties(Duration.ofDays(180));
    MongoSharedFolderAuditSink sink = new MongoSharedFolderAuditSink(repository, properties);
    Instant occurred = Instant.parse("2026-07-18T12:00:00Z");
    var command = new SharedFolderAuditCommand(
        "account-1", "RECYCLE", "docs/report.pdf", occurred, "203.0.113.8",
        42L, "accepted", null);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    sink.record(command);

    var captor = org.mockito.ArgumentCaptor.forClass(SharedFolderAuditEvent.class);
    verify(repository).save(captor.capture());
    SharedFolderAuditEvent event = captor.getValue();
    assertThat(event.accountId()).isEqualTo("account-1");
    assertThat(event.action()).isEqualTo("RECYCLE");
    assertThat(event.relativePath()).isEqualTo("docs/report.pdf");
    assertThat(event.expiresAt()).isEqualTo(occurred.plus(Duration.ofDays(180)));
    assertThat(event.toString()).doesNotContain("Authorization", "Bearer", "A:\\Shared");
  }

  @Test
  void expiresAtDeclaresAZeroSecondMongoTtlIndex() throws ReflectiveOperationException {
    Indexed indexed = SharedFolderAuditEvent.class.getDeclaredField("expiresAt")
        .getAnnotation(Indexed.class);

    assertThat(indexed).isNotNull();
    assertThat(indexed.expireAfter()).isEqualTo("0s");
  }

  @Test
  void filteredOutcomeAndPathQueriesHaveTimeOrderedCompoundIndexes() {
    CompoundIndexes indexes = SharedFolderAuditEvent.class.getAnnotation(CompoundIndexes.class);

    assertThat(indexes).isNotNull();
    assertThat(java.util.Arrays.stream(indexes.value()).map(index -> index.def()))
        .contains("{'outcome': 1, 'occurredAt': -1}",
            "{'relativePath': 1, 'occurredAt': -1}");
  }

  private SharedFolderProperties properties(Duration auditRetention) {
    return new SharedFolderProperties(
        Path.of("shared"), Path.of("system"), DataSize.ofGigabytes(10),
        DataSize.ofMegabytes(8), DataSize.ofBytes(1), DataSize.ofGigabytes(1),
        Duration.ofDays(30), auditRetention, true);
  }
}
