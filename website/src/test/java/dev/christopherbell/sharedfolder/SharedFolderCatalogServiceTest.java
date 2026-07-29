package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.SharedFolderCatalogProperties;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntryType;
import dev.christopherbell.sharedfolder.model.SharedDirectoryResponse;
import dev.christopherbell.sharedfolder.model.SharedFolderCatalogFreshness;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import dev.christopherbell.sharedfolder.model.SharedFolderSearchRequest;
import dev.christopherbell.sharedfolder.service.SharedFolderBrowserService;
import dev.christopherbell.sharedfolder.service.SharedFolderCatalogInvalidation;
import dev.christopherbell.sharedfolder.service.SharedFolderCatalogService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class SharedFolderCatalogServiceTest {
  private static final Instant MODIFIED_AT = Instant.parse("2026-07-25T12:00:00Z");

  @Test
  void requestReturnsBuildingWithoutEnumeratingOnTheRequestThread() {
    SharedFolderBrowserService browser = Mockito.mock(SharedFolderBrowserService.class);
    when(browser.list("")).thenReturn(directory("", List.of(file("track.mp3", "track.mp3"))));
    ManualExecutor executor = new ManualExecutor();
    SharedFolderCatalogService catalog = catalog(browser, new MutableClock(MODIFIED_AT), executor,
        properties(100, 20, 10, Duration.ofSeconds(5)));

    var building = catalog.search("track");

    assertThat(building.entries()).isEmpty();
    assertThat(catalog.status().freshness()).isEqualTo(SharedFolderCatalogFreshness.BUILDING);
    assertThat(executor.queued()).isEqualTo(1);
    verify(browser, never()).list("");

    executor.runNext();

    assertThat(catalog.search("track").entries()).extracting(SharedDirectoryEntry::path)
        .containsExactly("track.mp3");
    assertThat(catalog.status().freshness()).isEqualTo(SharedFolderCatalogFreshness.FRESH);
  }

  @Test
  void entryDirectoryAndDepthBudgetsPublishABoundedPartialSnapshot() {
    SharedFolderBrowserService browser = Mockito.mock(SharedFolderBrowserService.class);
    when(browser.list("")).thenReturn(directory("", List.of(
        directoryEntry("A", "A"), directoryEntry("B", "B"), file("one.mp3", "one.mp3"))));
    when(browser.list("A")).thenReturn(directory("A", List.of(
        directoryEntry("Deep", "A/Deep"), file("two.mp3", "A/two.mp3"))));
    ManualExecutor executor = new ManualExecutor();
    SharedFolderCatalogService catalog = catalog(browser, new MutableClock(MODIFIED_AT), executor,
        properties(4, 2, 1, Duration.ofSeconds(5)));

    catalog.refreshAsync();
    executor.runNext();

    assertThat(catalog.status().partial()).isTrue();
    assertThat(catalog.status().entryCount()).isLessThanOrEqualTo(4);
    verify(browser).list("");
    verify(browser).list("A");
    verify(browser, never()).list("B");
    verify(browser, never()).list("A/Deep");
  }

  @Test
  void inaccessibleChildMarksPartialButKeepsReachableEntries() {
    SharedFolderBrowserService browser = Mockito.mock(SharedFolderBrowserService.class);
    when(browser.list("")).thenReturn(directory("", List.of(
        directoryEntry("Private", "Private"), file("track.mp3", "track.mp3"))));
    when(browser.list("Private")).thenThrow(new ResponseStatusException(
        org.springframework.http.HttpStatus.NOT_FOUND));
    ManualExecutor executor = new ManualExecutor();
    SharedFolderCatalogService catalog = catalog(browser, new MutableClock(MODIFIED_AT), executor,
        properties(100, 20, 10, Duration.ofSeconds(5)));

    catalog.refreshAsync();
    executor.runNext();

    assertThat(catalog.status().partial()).isTrue();
    assertThat(catalog.search("track").entries()).extracting(SharedDirectoryEntry::path)
        .containsExactly("track.mp3");
  }

  @Test
  void timeoutPublishesPartialAndRootFailurePreservesLastKnownGood() {
    SharedFolderBrowserService browser = Mockito.mock(SharedFolderBrowserService.class);
    MutableClock clock = new MutableClock(MODIFIED_AT);
    when(browser.list("")).thenAnswer(ignored -> {
      clock.advance(Duration.ofSeconds(2));
      return directory("", List.of(
          directoryEntry("Pending", "Pending"), file("first.mp3", "first.mp3")));
    });
    ManualExecutor executor = new ManualExecutor();
    SharedFolderCatalogService catalog = catalog(browser, clock, executor,
        properties(100, 20, 10, Duration.ofSeconds(1)));

    catalog.refreshAsync();
    executor.runNext();
    assertThat(catalog.status().partial()).isTrue();

    when(browser.list("")).thenThrow(new IllegalStateException("offline"));
    catalog.invalidate(SharedFolderCatalogInvalidation.MUTATION);
    executor.runNext();

    assertThat(catalog.status().freshness()).isEqualTo(SharedFolderCatalogFreshness.FAILED);
    assertThat(catalog.search("first").entries()).extracting(SharedDirectoryEntry::path)
        .containsExactly("first.mp3");
  }

  @Test
  void invalidationCancelsQueuedGenerationAndOnlyNewestResultPublishes() {
    SharedFolderBrowserService browser = Mockito.mock(SharedFolderBrowserService.class);
    when(browser.list("")).thenReturn(directory("", List.of(file("new.mp3", "new.mp3"))));
    ManualExecutor executor = new ManualExecutor();
    SharedFolderCatalogService catalog = catalog(browser, new MutableClock(MODIFIED_AT), executor,
        properties(100, 20, 10, Duration.ofSeconds(5)));

    catalog.refreshAsync();
    catalog.invalidate(SharedFolderCatalogInvalidation.MUTATION);
    executor.runNext();
    executor.runNext();

    assertThat(catalog.status().generation()).isEqualTo(2);
    assertThat(catalog.search("new").entries()).extracting(SharedDirectoryEntry::path)
        .containsExactly("new.mp3");
    verify(browser, Mockito.times(1)).list("");
  }

  @Test
  void queryValidationStillRejectsBlankAndOverlongValuesWithoutSchedulingWork() {
    ManualExecutor executor = new ManualExecutor();
    SharedFolderCatalogService catalog = catalog(Mockito.mock(SharedFolderBrowserService.class),
        new MutableClock(MODIFIED_AT), executor, properties(100, 20, 10, Duration.ofSeconds(5)));

    assertThatThrownBy(() -> catalog.search("   "))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
    assertThatThrownBy(() -> catalog.search("x".repeat(201)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
    assertThat(executor.queued()).isZero();
  }

  @Test
  void searchPagesInStablePathOrderWithoutDuplicates() {
    SharedFolderBrowserService browser = Mockito.mock(SharedFolderBrowserService.class);
    when(browser.list("")).thenReturn(directory("", List.of(
        file("report.txt", "z/report.txt"),
        file("REPORT.txt", "A/REPORT.txt"),
        file("report.txt", "a/report.txt"))));
    ManualExecutor executor = new ManualExecutor();
    SharedFolderCatalogService catalog = catalog(browser, new MutableClock(MODIFIED_AT), executor,
        properties(100, 20, 10, Duration.ofSeconds(5)));
    catalog.refreshAsync();
    executor.runNext();

    var first = catalog.search(new SharedFolderSearchRequest("report", null, 2));
    var second = catalog.search(new SharedFolderSearchRequest("report", first.nextCursor(), 2));

    assertThat(first.entries()).extracting(SharedDirectoryEntry::path)
        .containsExactly("A/REPORT.txt", "a/report.txt");
    assertThat(first.nextCursor()).isNotBlank();
    assertThat(second.entries()).extracting(SharedDirectoryEntry::path)
        .containsExactly("z/report.txt");
    assertThat(second.nextCursor()).isNull();
    assertThat(second.generation()).isEqualTo(first.generation());
  }

  @Test
  void searchRejectsCursorAfterCatalogGenerationChanges() {
    SharedFolderBrowserService browser = Mockito.mock(SharedFolderBrowserService.class);
    when(browser.list("")).thenReturn(directory("", List.of(
        file("report-1.txt", "report-1.txt"), file("report-2.txt", "report-2.txt"))));
    ManualExecutor executor = new ManualExecutor();
    SharedFolderCatalogService catalog = catalog(browser, new MutableClock(MODIFIED_AT), executor,
        properties(100, 20, 10, Duration.ofSeconds(5)));
    catalog.refreshAsync();
    executor.runNext();
    var first = catalog.search(new SharedFolderSearchRequest("report", null, 1));

    catalog.invalidate(SharedFolderCatalogInvalidation.MUTATION);
    executor.runNext();

    assertThatThrownBy(() -> catalog.search(
        new SharedFolderSearchRequest("report", first.nextCursor(), 1)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
  }

  private SharedFolderCatalogService catalog(
      SharedFolderBrowserService browser,
      Clock clock,
      ManualExecutor executor,
      SharedFolderCatalogProperties properties) {
    return new SharedFolderCatalogService(browser, clock, properties, executor);
  }

  private SharedFolderCatalogProperties properties(
      int maxEntries, int maxDirectories, int maxDepth, Duration maxDuration) {
    return new SharedFolderCatalogProperties(
        maxEntries, maxDirectories, maxDepth, maxDuration, Duration.ofSeconds(15), 25);
  }

  private SharedDirectoryResponse directory(String path, List<SharedDirectoryEntry> entries) {
    return new SharedDirectoryResponse(path, entries);
  }

  private SharedDirectoryEntry directoryEntry(String name, String path) {
    return new SharedDirectoryEntry(name, path, SharedDirectoryEntryType.DIRECTORY, 0, MODIFIED_AT,
        SharedFolderPreviewKind.NONE);
  }

  private SharedDirectoryEntry file(String name, String path) {
    return new SharedDirectoryEntry(name, path, SharedDirectoryEntryType.FILE, 1, MODIFIED_AT,
        SharedFolderPreviewKind.AUDIO);
  }

  private static final class MutableClock extends Clock {
    private Instant value;

    private MutableClock(Instant value) {
      this.value = value;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return value;
    }

    private void advance(Duration duration) {
      value = value.plus(duration);
    }
  }

  private static final class ManualExecutor extends AbstractExecutorService {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private boolean shutdown;

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    private int queued() {
      return tasks.size();
    }

    private void runNext() {
      Runnable task = tasks.removeFirst();
      task.run();
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      List<Runnable> remaining = List.copyOf(tasks);
      tasks.clear();
      return remaining;
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown && tasks.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return isTerminated();
    }
  }
}
