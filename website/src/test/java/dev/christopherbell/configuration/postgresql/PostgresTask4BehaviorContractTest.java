package dev.christopherbell.configuration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.persistence.PostgresApplicationLeaseStore;
import dev.christopherbell.configuration.persistence.PostgresqlConstraintViolationCause;
import dev.christopherbell.music.catalog.MusicIndexStatus;
import dev.christopherbell.music.catalog.MusicQuery;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.catalog.PostgresMusicCatalogQueryRepository;
import dev.christopherbell.music.catalog.PostgresMusicTrackRepository;
import dev.christopherbell.music.library.MusicPlaylist;
import dev.christopherbell.music.library.PostgresMusicPlaylistRepository;
import dev.christopherbell.music.metadata.MusicMetadataEdit;
import dev.christopherbell.music.metadata.PostgresMusicMetadataEditRepository;
import dev.christopherbell.music.radio.MusicQueueState;
import dev.christopherbell.music.radio.MusicRadioHistoryEvent;
import dev.christopherbell.music.radio.MusicRadioState;
import dev.christopherbell.music.radio.PostgresMusicRadioHistoryRepository;
import dev.christopherbell.music.radio.PostgresMusicRuntimeStateRepository;
import dev.christopherbell.music.security.MusicAccessPrincipalType;
import dev.christopherbell.music.security.PostgresMusicAccessAttemptRepository;
import dev.christopherbell.sharedfolder.audit.PostgresSharedFolderAuditRepository;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditEvent;
import dev.christopherbell.sharedfolder.maintenance.PostgresSharedFolderMaintenanceLeaseStore;
import dev.christopherbell.sharedfolder.media.MediaJob;
import dev.christopherbell.sharedfolder.media.MediaJobStatus;
import dev.christopherbell.sharedfolder.media.MediaOutputProfile;
import dev.christopherbell.sharedfolder.media.PostgresMediaJobRepository;
import dev.christopherbell.sharedfolder.radio.PostgresSharedFolderRadioRepository;
import dev.christopherbell.sharedfolder.radio.SharedFolderRadioDocument;
import dev.christopherbell.sharedfolder.recycle.PostgresSharedFolderRecycleRepository;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleItem;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleState;
import dev.christopherbell.sharedfolder.service.PostgresSharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecovery;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryState;
import dev.christopherbell.sharedfolder.upload.PostgresSharedFolderUploadSessionRepository;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadFinalizationState;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSession;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadState;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;

/** Real PostgreSQL 18 contracts for Task 4 media persistence and concurrent ownership. */
@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresTask4BehaviorContractTest {
  private static final Instant NOW = Instant.parse("2026-08-13T20:00:00Z");
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    var accounts = new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    accounts.save(account("media-a", "media-a@example.test", "mediaa"));
    accounts.save(account("media-b", "media-b@example.test", "mediab"));
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Test
  void musicContractsPreservePathsOrderingCasAggregationAndBoundedQueries() {
    database.jdbc().sql("delete from " + table("music", "queue_entry")).update();
    database.jdbc().sql("delete from " + table("music", "runtime_state")).update();
    var tracks = new PostgresMusicTrackRepository(database.jdbc(), database.schemas());
    tracks.save(track("track-a", "album/a.mp3", "Alpha", true));
    tracks.save(track("track-b", "album/b.mp3", "Beta", false));
    assertThat(tracks.findByPath("album/a.mp3")).isPresent();
    assertThat(tracks.updatePreferences("track-a", true, false, false, true)).isTrue();
    assertThat(tracks.updatePreferences("track-a", true, false, true, false)).isFalse();
    assertThatThrownBy(() -> tracks.save(track("track-invalid", "album\\bad.mp3", "Bad", false)))
        .isInstanceOf(IllegalArgumentException.class);

    var catalog = new PostgresMusicCatalogQueryRepository(database.jdbc(), database.schemas());
    assertThat(catalog.search(new MusicQuery("alpha", null, null, null, null, null, 0, 10))
        .tracks()).extracting(MusicTrack::id).containsExactly("track-a");
    assertThat(catalog.radioCandidates(10)).extracting(MusicTrack::id).containsExactly("track-b");

    var playlists = new PostgresMusicPlaylistRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var saved = playlists.save(new MusicPlaylist("playlist", "ordered", "Ordered",
        List.of("track-b", "track-a"), null, "media-a", NOW));
    assertThat(saved.trackIds()).containsExactly("track-b", "track-a");
    var stale = saved;
    var changed = playlists.save(new MusicPlaylist(saved.id(), saved.normalizedName(), saved.name(),
        List.of("track-a", "track-b"), saved.version(), saved.updatedByAccountId(), NOW.plusSeconds(1)));
    assertThat(changed.trackIds()).containsExactly("track-a", "track-b");
    assertThatThrownBy(() -> playlists.save(stale)).isInstanceOf(OptimisticLockingFailureException.class);
    assertThatThrownBy(() -> playlists.save(new MusicPlaylist(changed.id(), changed.normalizedName(),
        changed.name(), List.of("missing-track"), changed.version(), changed.updatedByAccountId(),
        NOW.plusSeconds(2))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(DuplicateKeyException.class)
        .hasCauseInstanceOf(PostgresqlConstraintViolationCause.class)
        .hasMessage("PostgreSQL rejected music playlist data.")
        .hasMessageNotContaining("missing-track")
        .hasMessageNotContaining("insert into");
    assertThat(playlists.findById("playlist").orElseThrow().trackIds())
        .containsExactly("track-a", "track-b");

    assertThatThrownBy(() -> playlists.save(new MusicPlaylist(
        "playlist-duplicate", changed.normalizedName(), "Duplicate", List.of(), null,
        changed.updatedByAccountId(), NOW.plusSeconds(3))))
        .isInstanceOf(DuplicateKeyException.class)
        .hasCauseInstanceOf(PostgresqlConstraintViolationCause.class)
        .hasMessage("PostgreSQL rejected a duplicate music playlist identity.")
        .hasMessageNotContaining(changed.normalizedName())
        .hasMessageNotContaining("insert into");
    assertThat(playlists.findById("playlist").orElseThrow().trackIds())
        .containsExactly("track-a", "track-b");

    var edits = new PostgresMusicMetadataEditRepository(database.jdbc(), database.schemas());
    var edit = edits.save(metadataEdit(null));
    assertThat(edits.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(NOW.plusSeconds(3_601)))
        .containsExactly(edit);
    var applied = edits.save(edit.applied("replacement"));
    assertThatThrownBy(() -> edits.save(edit.applied("stale")))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(applied.version()).isEqualTo(1);

    var history = new PostgresMusicRadioHistoryRepository(database.jdbc(), database.schemas());
    history.save(history("history-1", 1, "track-a"));
    history.save(history("history-2", 2, "track-b"));
    assertThat(history.findTop100ByOrderByStationSequenceDesc())
        .extracting(MusicRadioHistoryEvent::id).containsExactly("history-2", "history-1");

    var runtime = new PostgresMusicRuntimeStateRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var queue = runtime.saveQueue(queue(null, "queue-a", "track-a"));
    var radio = runtime.saveRadio(radio(null, 1, "track-a"));
    var updatedQueue = runtime.saveQueue(queue(queue.version(), "queue-b", "track-b"));
    assertThat(updatedQueue.entries()).extracting(MusicQueueState.Entry::id).containsExactly("queue-b");
    assertThatThrownBy(() -> runtime.saveQueue(queue(queue.version(), "stale", "track-a")))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(runtime.findRadio()).contains(radio);

    var attempts = new PostgresMusicAccessAttemptRepository(database.jdbc(), database.schemas());
    attempts.record("attempt", MusicAccessPrincipalType.IP, "127.0.0.1", "denied", NOW,
        NOW.plus(Duration.ofDays(30)));
    var aggregated = attempts.record("attempt", MusicAccessPrincipalType.IP, "127.0.0.1", "denied",
        NOW.plusSeconds(1), NOW.plus(Duration.ofDays(30)).plusSeconds(1));
    assertThat(aggregated.count()).isEqualTo(2);
    assertThat(attempts.recent(1)).containsExactly(aggregated);
    assertThat(attempts.deleteExpired(NOW.plus(Duration.ofDays(31)), 1)).isOne();
    assertThat(attempts.deleteExpired(NOW.plus(Duration.ofDays(31)), 1)).isZero();
  }

  @Test
  void musicRuntimeRepositoryPreservesAndUpdatesLegacyStateIdentity() {
    database.jdbc().sql("delete from " + table("music", "queue_entry")).update();
    database.jdbc().sql("delete from " + table("music", "runtime_state")).update();
    database.jdbc().sql("""
            insert into %s (runtime_state_id, state_kind, version)
            values ('legacy-queue-state', 'QUEUE', 4)
            """.formatted(table("music", "runtime_state"))).update();
    var runtime = new PostgresMusicRuntimeStateRepository(
        database.managedJdbc(), database.schemas(), database.transactions());

    var loaded = runtime.findQueue().orElseThrow();
    var saved = runtime.saveQueue(
        new MusicQueueState(MusicQueueState.ID, List.of(), loaded.version()));

    assertThat(saved.version()).isEqualTo(5L);
    assertThat(database.jdbc().sql("""
            select runtime_state_id, version from %s where state_kind = 'QUEUE'
            """.formatted(table("music", "runtime_state")))
        .query((row, ignored) -> Map.entry(
            row.getString("runtime_state_id"), row.getLong("version"))).single())
        .isEqualTo(Map.entry("legacy-queue-state", 5L));
  }

  @ParameterizedTest(name = "rejects Windows-unsafe persisted path: {0}")
  @MethodSource("windowsUnsafePersistedPaths")
  void postgresAdaptersRejectEveryWindowsUnsafeRelativePath(String path) {
    var tracks = new PostgresMusicTrackRepository(database.jdbc(), database.schemas());

    assertThatThrownBy(() -> tracks.save(track("unsafe-" + Integer.toUnsignedString(path.hashCode()),
        path, "Unsafe", false)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static List<String> windowsUnsafePersistedPaths() {
    return List.of(
        "C:/Windows/file.mp3",
        "C:relative.mp3",
        "file.mp3:stream",
        "album/control\n.mp3",
        "album/../secret.mp3");
  }

  @ParameterizedTest(name = "invalid lease identity is rejected before SQL: {0}")
  @MethodSource("invalidLeaseIdentities")
  void applicationLeaseRejectsInvalidIdentityWithoutChangingAnyRow(
      String leaseName, String ownerId) {
    var leases = new PostgresApplicationLeaseStore(database.jdbc(), database.schemas());
    long rowsBefore = count("platform", "application_lease");

    assertThatThrownBy(() -> leases.tryAcquire(leaseName, ownerId, Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(count("platform", "application_lease")).isEqualTo(rowsBefore);
  }

  static List<Arguments> invalidLeaseIdentities() {
    return List.of(
        Arguments.of("", "valid-owner"),
        Arguments.of("valid-lease", ""),
        Arguments.of("l".repeat(129), "valid-owner"),
        Arguments.of("valid-lease", "o".repeat(129)));
  }

  @Test
  void databaseTimeLeasesHaveOneWinnerAndMonotonicFencingAcrossConnections() throws Exception {
    database.jdbc().sql("delete from " + table("shared_folder", "maintenance_lease")).update();
    database.jdbc().sql("delete from " + table("platform", "application_lease")).update();
    var maintenance = new PostgresSharedFolderMaintenanceLeaseStore(
        database.jdbc(), database.schemas());
    assertThat(maintenance.tryAcquire("owner-a", Duration.ofMinutes(1))).isPresent();
    assertThat(maintenance.tryAcquire("owner-b", Duration.ofMinutes(1))).isEmpty();
    long firstFence = database.jdbc().sql("select fence_token from "
        + table("shared_folder", "maintenance_lease")).query(Long.class).single();
    database.jdbc().sql("update %s set expires_at = current_timestamp - interval '1 second'"
        .formatted(table("shared_folder", "maintenance_lease"))).update();
    assertSingleWinner(
        () -> claimMaintenance("owner-b"), () -> claimMaintenance("owner-c"));
    assertThat(database.jdbc().sql("select fence_token from "
        + table("shared_folder", "maintenance_lease")).query(Long.class).single())
        .isGreaterThan(firstFence);

    var application = new PostgresApplicationLeaseStore(database.jdbc(), database.schemas());
    var first = application.tryAcquire("music", "owner-a", Duration.ofMinutes(1)).orElseThrow();
    assertThat(application.tryAcquire("music", "owner-b", Duration.ofMinutes(1))).isEmpty();
    database.jdbc().sql("""
            update %s set expires_at = current_timestamp - interval '1 second'
            where lease_name = 'music'
            """.formatted(table("platform", "application_lease"))).update();
    var next = application.tryAcquire("music", "owner-b", Duration.ofMinutes(1)).orElseThrow();
    assertThat(next.fenceToken()).isGreaterThan(first.fenceToken());
    assertThat(application.renew(first, Duration.ofMinutes(1))).isEmpty();
    assertThat(application.release(first)).isFalse();
    assertThat(application.release(next)).isTrue();
  }

  @Test
  void maintenanceLeaseTakesOverAnExpiredLegacyRowWithoutOwnershipAtFenceOne() {
    database.jdbc().sql("delete from " + table("shared_folder", "maintenance_lease")).update();
    database.jdbc().sql("""
            insert into %s
              (lease_name, owner_token, fence_token, acquired_at, expires_at)
            values ('shared-folder-maintenance', null, null,
              timestamptz '2026-08-01T00:00:00Z', timestamptz '2026-08-01T00:01:00Z')
            """.formatted(table("shared_folder", "maintenance_lease"))).update();

    var grant = new PostgresSharedFolderMaintenanceLeaseStore(database.jdbc(), database.schemas())
        .tryAcquire("legacy-takeover", Duration.ofMinutes(1))
        .orElseThrow();

    assertThat(grant.ownerId()).isEqualTo("legacy-takeover");
    assertThat(grant.fenceToken()).isOne();
  }

  @Test
  void applicationLeaseTakesOverAnExpiredLegacyRowWithoutOwnershipAtFenceOne() {
    database.jdbc().sql("delete from " + table("platform", "application_lease")).update();
    database.jdbc().sql("""
            insert into %s
              (lease_name, owner_token, fence_token, acquired_at, expires_at)
            values ('legacy-application', null, null,
              timestamptz '2026-08-01T00:00:00Z', timestamptz '2026-08-01T00:01:00Z')
            """.formatted(table("platform", "application_lease"))).update();

    var grant = new PostgresApplicationLeaseStore(database.jdbc(), database.schemas())
        .tryAcquire("legacy-application", "legacy-owner", Duration.ofMinutes(1))
        .orElseThrow();

    assertThat(grant.ownerId()).isEqualTo("legacy-owner");
    assertThat(grant.fenceToken()).isOne();
  }

  @Test
  void sharedFolderAuditPreservesUnknownClientOrigin() {
    var event = new PostgresSharedFolderAuditRepository(database.jdbc(), database.schemas()).save(
        new SharedFolderAuditEvent("unknown-origin", "unknown", "READ", "folder/file.txt",
            1L, "SUCCESS", null, "unknown", Instant.parse("2026-08-01T00:00:00Z"),
            Instant.parse("2027-08-01T00:00:00Z")));

    assertThat(event.clientIp()).isEqualTo("unknown");
  }

  @Test
  void retentionDeleteRechecksRowsRefreshedByAnIndependentConnection() throws Exception {
    Instant cutoff = Instant.parse("1900-01-02T00:00:00Z");
    Instant refreshedExpiry = Instant.parse("2200-01-01T00:00:00Z");
    var attempts = new PostgresMusicAccessAttemptRepository(database.jdbc(), database.schemas());
    attempts.record("retention-race-access", MusicAccessPrincipalType.IP, "127.0.0.1", "denied",
        cutoff.minusSeconds(2), cutoff.minusSeconds(1));

    try (var executor = Executors.newSingleThreadExecutor();
         var refresher = schemas.openDatabase();
         var deleter = schemas.openDatabase()) {
      refresher.connection().setAutoCommit(false);
      refresher.jdbc().sql("""
              update %s set expires_at = :expiresAt where access_attempt_id = :id
              """.formatted(refresher.schemas().qualifiedTable("music", "access_attempt")))
          .param("expiresAt", refreshedExpiry.atOffset(ZoneOffset.UTC))
          .param("id", "retention-race-access").update();
      int deletingBackend = deleter.jdbc().sql("select pg_backend_pid()")
          .query(Integer.class).single();
      var deletion = executor.submit(() ->
          new PostgresMusicAccessAttemptRepository(deleter.jdbc(), deleter.schemas())
              .deleteExpired(cutoff, 1));
      awaitBlockedByRefresh(deletingBackend);
      refresher.connection().commit();

      assertThat(deletion.get(10, TimeUnit.SECONDS)).isZero();
    }
    assertThat(database.jdbc().sql("""
            select expires_at from %s where access_attempt_id = :id
            """.formatted(table("music", "access_attempt")))
        .param("id", "retention-race-access").query(OffsetDateTime.class).single())
        .isEqualTo(refreshedExpiry.atOffset(ZoneOffset.UTC));

    var audits = new PostgresSharedFolderAuditRepository(database.jdbc(), database.schemas());
    audits.save(new SharedFolderAuditEvent(
        "retention-race-audit", "media-a", "READ", null, null, "SUCCESS", null,
        "127.0.0.1", cutoff.minusSeconds(2), cutoff.minusSeconds(1)));
    try (var executor = Executors.newSingleThreadExecutor();
         var refresher = schemas.openDatabase();
         var deleter = schemas.openDatabase()) {
      refresher.connection().setAutoCommit(false);
      refresher.jdbc().sql("""
              update %s set expires_at = :expiresAt where audit_event_id = :id
              """.formatted(refresher.schemas().qualifiedTable("shared_folder", "audit_event")))
          .param("expiresAt", refreshedExpiry.atOffset(ZoneOffset.UTC))
          .param("id", "retention-race-audit").update();
      int deletingBackend = deleter.jdbc().sql("select pg_backend_pid()")
          .query(Integer.class).single();
      var deletion = executor.submit(() ->
          new PostgresSharedFolderAuditRepository(deleter.jdbc(), deleter.schemas())
              .deleteExpired(cutoff, 1));
      awaitBlockedByRefresh(deletingBackend);
      refresher.connection().commit();

      assertThat(deletion.get(10, TimeUnit.SECONDS)).isZero();
    }
    assertThat(database.jdbc().sql("""
            select expires_at from %s where audit_event_id = :id
            """.formatted(table("shared_folder", "audit_event")))
        .param("id", "retention-race-audit").query(OffsetDateTime.class).single())
        .isEqualTo(refreshedExpiry.atOffset(ZoneOffset.UTC));
  }

  private static void awaitBlockedByRefresh(int deletingBackend) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer blockers = database.jdbc()
          .sql("select cardinality(pg_blocking_pids(:backend))")
          .param("backend", deletingBackend).query(Integer.class).single();
      if (blockers != null && blockers > 0) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Retention delete did not block on the independent refresh.");
  }

  @Test
  void boundedTaskFourQueriesHaveConstantCountsAndUseDeclaredIndexes() throws Exception {
    var executions = new AtomicInteger();
    var catalog = new PostgresMusicCatalogQueryRepository(
        countedJdbc(executions), database.schemas());
    assertConstantQueries(executions, 6,
        () -> catalog.search(new MusicQuery(null, null, null, null, null, null, 0, 1)),
        () -> catalog.search(new MusicQuery(null, null, null, null, null, null, 0, 100)));

    database.jdbc().sql("set enable_seqscan = off").update();
    try {
      assertThat(explain(("select * from %s where excluded_from_radio = false "
          + "and missing_since is null and index_status = 'READY' order by "
          + "excluded_from_radio, favorite desc, artist, album, track_id limit 100")
          .formatted(table("music", "track"))))
          .contains("track__track_radio_candidate");
      assertThat(explainDue("music", "access_attempt", "expires_at",
          "expires_at, access_attempt_id")).contains("access_attempt__access_attempt_expiration");
      assertThat(explainDue("shared_folder", "audit_event", "expires_at",
          "expires_at, audit_event_id")).contains("audit_event__audit_event_expiration");
      assertThat(explain(("select * from %s where artifacts_cleaned = false "
          + "and cleanup_after <= timestamptz '2026-08-13T20:00:00Z' "
          + "order by cleanup_after, last_accessed_at, media_job_id limit 100")
          .formatted(table("shared_folder", "media_job"))))
          .contains("media_job__media_cleanup_due");
      assertThat(explain(("select * from %s where operation_lease_expires_at "
          + "<= timestamptz '2026-08-13T20:00:00Z' order by "
          + "operation_lease_expires_at, updated_at, mutation_recovery_id limit 100")
          .formatted(table("shared_folder", "mutation_recovery"))))
          .contains("mutation_recovery__mutation_recovery_lease");
      assertThat(explain(("select * from %s where state = 'RECYCLED' and retry_after "
          + "<= timestamptz '2026-08-13T20:00:00Z' order by "
          + "state, retry_after, recycle_item_id limit 100")
          .formatted(table("shared_folder", "recycle_item"))))
          .contains("recycle_item__recycle_recovery_due");
      assertThat(explain(("select * from %s where owner_id = 'media-a' and state = 'ACTIVE' "
          + "order by updated_at desc, upload_session_id limit 100")
          .formatted(table("shared_folder", "upload_session"))))
          .contains("upload_session__upload_owner_state");
    } finally {
      database.jdbc().sql("reset enable_seqscan").update();
    }
  }

  @Test
  void sharedFolderRepositoriesPreserveDurableIntentOrderingAndOptimisticState() {
    var audit = new PostgresSharedFolderAuditRepository(database.jdbc(), database.schemas());
    var event = audit.save(new SharedFolderAuditEvent(null, "media-a", "DOWNLOAD", "folder/file.mp4",
        100L, "SUCCESS", null, "127.0.0.1", NOW, NOW.plus(Duration.ofDays(30))));
    assertThat(event.id()).isNotBlank();
    assertThat(audit.search("media-a", "DOWNLOAD", "SUCCESS", "folder/file.mp4",
        NOW.minusSeconds(1), NOW.plusSeconds(1), 10)).containsExactly(event);

    var media = new PostgresMediaJobRepository(database.jdbc(), database.schemas());
    var mediaJob = media.save(mediaJob());
    assertThat(media.findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(
        "cache", MediaJobStatus.active())).contains(mediaJob);
    assertThat(media.cancelActive(mediaJob.getId(), "media-a", NOW.plusSeconds(1), NOW.plusSeconds(60)))
        .isOne();
    assertThat(media.findById(mediaJob.getId()).orElseThrow().getStatus())
        .isEqualTo(MediaJobStatus.CANCELED);

    var radio = new PostgresSharedFolderRadioRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var station = radio.save(SharedFolderRadioDocument.playing(1, "Music/a.mp3", NOW, 90.0,
        List.of(new SharedFolderRadioDocument.TrackDuration("Music/b.mp3", "token-b", 91.0))));
    assertThat(radio.findById(SharedFolderRadioDocument.ID).orElseThrow().knownDurations())
        .extracting(SharedFolderRadioDocument.TrackDuration::path).containsExactly("Music/b.mp3");
    radio.save(SharedFolderRadioDocument.empty(2, station.knownDurations(), station.version()));
    assertThatThrownBy(() -> radio.save(station)).isInstanceOf(OptimisticLockingFailureException.class);

    var recycle = new PostgresSharedFolderRecycleRepository(database.jdbc(), database.schemas());
    recycle.save(recycle("recycle-a", NOW));
    recycle.save(recycle("recycle-b", NOW.plusSeconds(1)));
    assertThat(recycle.findByStateOrderByDeletedAtDescIdDesc(
        SharedFolderRecycleState.RECYCLED, PageRequest.of(0, 1)).getContent())
        .extracting(SharedFolderRecycleItem::id).containsExactly("recycle-b");

    var uploads = new PostgresSharedFolderUploadSessionRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var upload = uploads.save(upload("upload-active", SharedFolderUploadState.ACTIVE, null));
    assertThat(uploads.findById(upload.getId()).orElseThrow().getChunkDigests())
        .containsEntry("chunk-0", "a".repeat(64));
    assertThat(uploads.findById(upload.getId()).orElseThrow().getChunkLengths())
        .containsEntry("chunk-0", 4L);
    assertThat(uploads.expireActive(upload.getId(), NOW.plusSeconds(120), NOW.plusSeconds(2))).isOne();
    assertThat(uploads.findDueForMaintenance(NOW.plusSeconds(3), PageRequest.of(0, 10)))
        .extracting(SharedFolderUploadSession::getId).contains("upload-active");
  }

  @Test
  void recoveryAndUploadClaimsAreAtomicAndRetryableAfterCrash() throws Exception {
    var recoveries = new PostgresSharedFolderMutationRecoveryRepository(
        database.jdbc(), database.schemas());
    var recovery = recoveries.save(recovery());
    setDatabaseRelativeTime("shared_folder", "mutation_recovery",
        "operation_lease_expires_at", "mutation_recovery_id", recovery.getId(), "1 minute");
    assertThat(claimRecovery("recovery-too-early")).isFalse();
    setDatabaseRelativeTime("shared_folder", "mutation_recovery",
        "operation_lease_expires_at", "mutation_recovery_id", recovery.getId(), "-1 second");
    assertSingleWinner(
        () -> claimRecovery("recovery-owner-a"),
        () -> claimRecovery("recovery-owner-b"));
    assertThat(recoveries.findById(recovery.getId()).orElseThrow().getOperationLeaseToken())
        .isIn("recovery-owner-a", "recovery-owner-b");

    var uploads = new PostgresSharedFolderUploadSessionRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    var upload = uploads.save(upload("upload-claim", SharedFolderUploadState.APPENDING, "append-owner"));
    setDatabaseRelativeTime("shared_folder", "upload_session",
        "append_lease_expires_at", "upload_session_id", upload.getId(), "1 minute");
    assertThat(claimUpload("append-too-early")).isFalse();
    setDatabaseRelativeTime("shared_folder", "upload_session",
        "append_lease_expires_at", "upload_session_id", upload.getId(), "-1 second");
    assertSingleWinner(
        () -> claimUpload("append-recovery-a"),
        () -> claimUpload("append-recovery-b"));
    assertThat(uploads.findById(upload.getId()).orElseThrow().getAppendLeaseToken())
        .isIn("append-recovery-a", "append-recovery-b");
  }

  private static boolean claimRecovery(String token) throws Exception {
    try (var connection = schemas.openDatabase()) {
      return new PostgresSharedFolderMutationRecoveryRepository(
          connection.jdbc(), connection.schemas())
          .claimExpiredOperationLease("recovery", "operation-owner",
              SharedFolderMutationRecoveryState.PREPARED, token,
              Duration.ofMinutes(1)).isPresent();
    }
  }

  private static boolean claimMaintenance(String token) throws Exception {
    try (var connection = schemas.openDatabase()) {
      return new PostgresSharedFolderMaintenanceLeaseStore(
          connection.jdbc(), connection.schemas())
          .tryAcquire(token, Duration.ofMinutes(1)).isPresent();
    }
  }

  private static boolean claimUpload(String token) throws Exception {
    try (var connection = schemas.openDatabase()) {
      return new PostgresSharedFolderUploadSessionRepository(
          connection.managedJdbc(), connection.schemas(), connection.transactions())
          .claimExpiredAppendLease("upload-claim", "append-owner", 0, token,
              Duration.ofMinutes(1)).isPresent();
    }
  }

  private static void assertSingleWinner(CheckedBoolean first, CheckedBoolean second) throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var a = executor.submit(() -> { start.await(); return first.run(); });
      var b = executor.submit(() -> { start.await(); return second.run(); });
      start.countDown();
      assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
    }
  }

  private static JdbcClient countedJdbc(AtomicInteger executions) {
    return JdbcClient.create(new AbstractDataSource() {
      @Override
      public Connection getConnection() throws SQLException {
        return countingConnection(database.dataSource().getConnection(), executions);
      }

      @Override
      public Connection getConnection(String username, String password) throws SQLException {
        return countingConnection(
            database.dataSource().getConnection(username, password), executions);
      }
    });
  }

  private static Connection countingConnection(Connection delegate, AtomicInteger executions) {
    return (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, arguments) -> {
          if (method.getName().startsWith("prepareStatement")) executions.incrementAndGet();
          try {
            return method.invoke(delegate, arguments);
          } catch (InvocationTargetException failure) {
            throw failure.getCause();
          }
        });
  }

  private static void assertConstantQueries(
      AtomicInteger executions, int expected, ThrowingQuery small, ThrowingQuery large)
      throws Exception {
    executions.set(0);
    small.run();
    assertThat(executions).hasValue(expected);
    executions.set(0);
    large.run();
    assertThat(executions).hasValue(expected);
  }

  private static String table(String schema, String name) {
    return database.schemas().qualifiedTable(schema, name);
  }

  private static long count(String schema, String name) {
    return database.jdbc().sql("select count(*) from " + table(schema, name))
        .query(Long.class).single();
  }

  private static String explain(String statement) {
    return String.join("\n", database.jdbc().sql("explain " + statement)
        .query(String.class).list());
  }

  private static String explainDue(
      String schema, String name, String dueColumn, String order) {
    return explain(("select * from %s where %s <= timestamptz '2026-08-13T20:00:00Z' "
        + "order by %s limit 100").formatted(table(schema, name), dueColumn, order));
  }

  private static void setDatabaseRelativeTime(
      String schema, String tableName, String timeColumn, String idColumn,
      String id, String interval) {
    database.jdbc().sql("update %s set %s = current_timestamp + interval '%s' where %s = :id"
        .formatted(table(schema, tableName), timeColumn, interval, idColumn))
        .param("id", id).update();
  }

  private static Account account(String id, String email, String username) {
    return Account.builder().id(id).createdOn(NOW).email(email).passwordHash("hash")
        .role(Role.USER).status(AccountStatus.ACTIVE).username(username).build();
  }

  private static MusicTrack track(String id, String path, String artist, boolean favorite) {
    return new MusicTrack(id, path, "token-" + id, null, id, artist, artist, "Album", 1, 1,
        "Genre", 2026, 90, "mp3", "mp3", null, favorite, false, MusicIndexStatus.READY,
        null, NOW, NOW, null);
  }

  private static MusicMetadataEdit metadataEdit(Long version) {
    return new MusicMetadataEdit("edit", "track-a", "album/a.mp3", "backup.bin", "a".repeat(64),
        "token-track-a", null, "mp3", 90, "media-a", NOW, NOW.plusSeconds(3_600),
        MusicMetadataEdit.Status.PREPARED, null, version);
  }

  private static MusicRadioHistoryEvent history(String id, long sequence, String trackId) {
    return new MusicRadioHistoryEvent(id, sequence, trackId, "token-" + trackId, "Artist",
        MusicRadioState.Source.RADIO, MusicRadioHistoryEvent.Outcome.PLAYED, NOW.plusSeconds(sequence));
  }

  private static MusicQueueState queue(Long version, String entryId, String trackId) {
    return new MusicQueueState(MusicQueueState.ID,
        List.of(new MusicQueueState.Entry(entryId, trackId, "token-" + trackId, "media-a", NOW)),
        version);
  }

  private static MusicRadioState radio(Long version, long sequence, String trackId) {
    return new MusicRadioState(MusicRadioState.ID, sequence, trackId, "token-" + trackId, NOW,
        90, MusicRadioState.Source.RADIO, null, version);
  }

  private static MediaJob mediaJob() {
    var job = new MediaJob();
    job.setId("media-job"); job.setOwnerId("media-a"); job.setSourcePath("video/source.mkv");
    job.setSourceSize(100); job.setSourceModifiedAt(NOW); job.setProfile(MediaOutputProfile.VIDEO_MP4);
    job.setProfileVersion(1); job.setCacheKey("cache"); job.setActiveCacheKey("active-cache");
    job.setStatus(MediaJobStatus.QUEUED); job.setReservedBytes(1_000); job.setCreatedAt(NOW);
    job.setUpdatedAt(NOW); job.setLastAccessedAt(NOW); job.setArtifactsCleaned(false);
    return job;
  }

  private static SharedFolderRecycleItem recycle(String id, Instant deletedAt) {
    return new SharedFolderRecycleItem(id, "folder/" + id, "media-a", deletedAt,
        deletedAt.plus(Duration.ofDays(30)), "recycle/" + id, 10, false, "fingerprint-" + id,
        SharedFolderRecycleState.RECYCLED, null, null, "identity-" + id, Instant.EPOCH);
  }

  private static SharedFolderMutationRecovery recovery() {
    var value = new SharedFolderMutationRecovery();
    value.setId("recovery"); value.setOwnerId("media-a"); value.setSourcePath("folder/source.txt");
    value.setDestinationParentPath("folder"); value.setName("target.txt");
    value.setSourceIdentity("source-identity"); value.setState(SharedFolderMutationRecoveryState.PREPARED);
    value.setOperationLeaseToken("operation-owner"); value.setOperationLeaseExpiresAt(NOW.minusSeconds(1));
    value.setCreatedAt(NOW); value.setUpdatedAt(NOW); return value;
  }

  private static SharedFolderUploadSession upload(
      String id, SharedFolderUploadState state, String appendLeaseToken) {
    var value = new SharedFolderUploadSession();
    value.setId(id); value.setOwnerId("media-a"); value.setParentPath("folder"); value.setName("upload.bin");
    value.setExpectedBytes(4); value.setExpectedSha256("b".repeat(64)); value.setNextOffset(0);
    value.setChunkDigests(Map.of("chunk-0", "a".repeat(64))); value.setChunkLengths(Map.of("chunk-0", 4L));
    value.setStagingKey("uploads/" + id); value.setState(state); value.setExpiresAt(NOW.plusSeconds(60));
    value.setCreatedAt(NOW); value.setUpdatedAt(NOW);
    if (state == SharedFolderUploadState.APPENDING) {
      value.setAppendLeaseToken(appendLeaseToken); value.setAppendLeaseExpiresAt(NOW.minusSeconds(1));
      value.setAppendOffset(0L); value.setAppendLength(4L); value.setAppendDigest("a".repeat(64));
      value.setAppendChunkKey("chunk-0");
    }
    return value;
  }

  @FunctionalInterface
  private interface CheckedBoolean { boolean run() throws Exception; }

  @FunctionalInterface
  private interface ThrowingQuery { void run() throws Exception; }
}
