package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntryType;
import dev.christopherbell.sharedfolder.model.SharedDirectoryResponse;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import dev.christopherbell.sharedfolder.service.SharedFolderBrowserService;
import dev.christopherbell.sharedfolder.service.SharedFolderCatalogService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class SharedFolderCatalogServiceTest {
  private static final Instant MODIFIED_AT = Instant.parse("2026-07-25T12:00:00Z");

  @Test
  void search_recursivelyMatchesCaseInsensitiveNamesAndPathsInBreadthFirstOrder() {
    SharedFolderBrowserService browser = Mockito.mock(SharedFolderBrowserService.class);
    when(browser.list("")).thenReturn(directory("", List.of(
        directoryEntry("Music", "Music"), directoryEntry("track-library", "track-library"),
        file("unrelated.txt", "unrelated.txt"))));
    when(browser.list("Music")).thenReturn(directory("Music", List.of(
        directoryEntry("archive", "Music/archive"),
        file("MiXeD tRaCk.flac", "Music/MiXeD tRaCk.flac"),
        file("Track-000.mp3", "Music/Track-000.mp3"))));
    when(browser.list("track-library")).thenReturn(directory("track-library", List.of(
        file("notes.txt", "track-library/notes.txt"))));
    when(browser.list("Music/archive")).thenReturn(directory("Music/archive", tracks()));

    var response = new SharedFolderCatalogService(browser, Clock.fixed(MODIFIED_AT, ZoneOffset.UTC))
        .search("  tRaCk  ");

    assertThat(response.query()).isEqualTo("tRaCk");
    assertThat(response.entries()).hasSize(200);
    assertThat(response.entries()).extracting(SharedDirectoryEntry::path)
        .startsWith("track-library", "Music/MiXeD tRaCk.flac", "Music/Track-000.mp3",
            "track-library/notes.txt")
        .endsWith("Music/archive/Track-196.mp3")
        .doesNotContain("unrelated.txt");
    assertThat(response.entries()).allSatisfy(entry -> assertThat(entry.path())
        .doesNotContain(":", "\\\\"));
    assertThat(response.truncated()).isTrue();
  }

  @Test
  void search_reusesItsImmutableCatalogForFifteenSecondsBeforeRefreshing() {
    SharedFolderBrowserService browser = Mockito.mock(SharedFolderBrowserService.class);
    MutableClock clock = new MutableClock(MODIFIED_AT);
    when(browser.list("")).thenReturn(directory("", List.of(file("first.txt", "first.txt"))));
    SharedFolderCatalogService catalog = new SharedFolderCatalogService(browser, clock);

    assertThat(catalog.search("first").entries()).extracting(SharedDirectoryEntry::path)
        .containsExactly("first.txt");
    when(browser.list("")).thenReturn(directory("", List.of(file("second.txt", "second.txt"))));

    assertThat(catalog.search("first").entries()).extracting(SharedDirectoryEntry::path)
        .containsExactly("first.txt");
    clock.advanceSeconds(15);

    assertThat(catalog.search("second").entries()).extracting(SharedDirectoryEntry::path)
        .containsExactly("second.txt");
  }

  @Test
  void search_rejectsBlankAndOverlongQueries() {
    SharedFolderCatalogService catalog = new SharedFolderCatalogService(
        Mockito.mock(SharedFolderBrowserService.class), Clock.fixed(MODIFIED_AT, ZoneOffset.UTC));

    assertThatThrownBy(() -> catalog.search("   "))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
    assertThatThrownBy(() -> catalog.search("x".repeat(201)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
  }

  @Test
  void audioTracksBelowMusic_returnsOnlyRecursiveAudioFilesUnderRootMusicIgnoringCase() {
    SharedFolderBrowserService browser = Mockito.mock(SharedFolderBrowserService.class);
    when(browser.list("")).thenReturn(directory("", List.of(
        directoryEntry("mUsIc", "mUsIc"), directoryEntry("Elsewhere", "Elsewhere"))));
    when(browser.list("mUsIc")).thenReturn(directory("mUsIc", List.of(
        directoryEntry("Live", "mUsIc/Live"),
        file("root.mp3", "mUsIc/root.mp3"),
        nonAudioFile("cover.jpg", "mUsIc/cover.jpg"))));
    when(browser.list("mUsIc/Live")).thenReturn(directory("mUsIc/Live", List.of(
        file("nested.flac", "mUsIc/Live/nested.flac"))));
    when(browser.list("Elsewhere")).thenReturn(directory("Elsewhere", List.of(
        file("outside.mp3", "Elsewhere/outside.mp3"))));

    var tracks = new SharedFolderCatalogService(browser, Clock.fixed(MODIFIED_AT, ZoneOffset.UTC))
        .audioTracksBelowMusic();

    assertThat(tracks).extracting(SharedDirectoryEntry::path)
        .containsExactly("mUsIc/root.mp3", "mUsIc/Live/nested.flac");
  }

  private List<SharedDirectoryEntry> tracks() {
    List<SharedDirectoryEntry> entries = new ArrayList<>();
    for (int number = 1; number <= 201; number++) {
      String filename = "Track-%03d.mp3".formatted(number);
      entries.add(file(filename, "Music/archive/" + filename));
    }
    return List.copyOf(entries);
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

  private SharedDirectoryEntry nonAudioFile(String name, String path) {
    return new SharedDirectoryEntry(name, path, SharedDirectoryEntryType.FILE, 1, MODIFIED_AT,
        SharedFolderPreviewKind.IMAGE);
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
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
      return instant;
    }

    private void advanceSeconds(long seconds) {
      instant = instant.plusSeconds(seconds);
    }
  }
}
