package dev.christopherbell.music.web;

import static dev.christopherbell.libs.api.APIVersion.V20260728;

import dev.christopherbell.music.library.MusicLibraryService;
import dev.christopherbell.music.library.MusicPlaylistView;
import dev.christopherbell.music.radio.MusicRadioHistoryEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Music-read shared library queries and Music-write global mutations. */
@RestController
@RequestMapping("/api/music" + V20260728 + "/library")
public final class MusicLibraryController {
  private final MusicLibraryService library;

  public MusicLibraryController(MusicLibraryService library) {
    this.library = library;
  }

  @GetMapping("/playlists")
  public ResponseEntity<List<MusicPlaylistView>> playlists() {
    return noStore(library.playlists());
  }

  @PostMapping("/playlists")
  public ResponseEntity<MusicPlaylistView> create(@Valid @RequestBody PlaylistCreate request) {
    return noStore(library.create(request.name(), request.trackIds()));
  }

  @PutMapping("/playlists/{id}")
  public ResponseEntity<MusicPlaylistView> update(
      @PathVariable @NotBlank @Size(max = 100) String id,
      @Valid @RequestBody PlaylistUpdate request) {
    return noStore(library.update(
        id, request.expectedVersion(), request.name(), request.trackIds()));
  }

  @DeleteMapping("/playlists/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable @NotBlank @Size(max = 100) String id,
      @RequestParam @Min(0) long expectedVersion) {
    library.delete(id, expectedVersion);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  @PatchMapping("/tracks/{id}/preferences")
  public ResponseEntity<MusicTrackView> preferences(
      @PathVariable @NotBlank @Size(max = 128) String id,
      @Valid @RequestBody PreferenceUpdate request) {
    return noStore(library.updatePreferences(
        id,
        request.expectedFavorite(),
        request.expectedExcludedFromRadio(),
        request.favorite(),
        request.excludedFromRadio()));
  }

  @GetMapping("/history")
  public ResponseEntity<List<MusicRadioHistoryEvent>> history(
      @RequestParam(defaultValue = "50") int limit) {
    return noStore(library.history(limit));
  }

  private <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  public record PlaylistCreate(
      @NotBlank @Size(max = 100) String name,
      @NotNull @Size(max = 1000) List<@NotBlank @Size(max = 128) String> trackIds) {}

  public record PlaylistUpdate(
      @Min(0) long expectedVersion,
      @NotBlank @Size(max = 100) String name,
      @NotNull @Size(max = 1000) List<@NotBlank @Size(max = 128) String> trackIds) {}

  public record PreferenceUpdate(
      boolean expectedFavorite,
      boolean expectedExcludedFromRadio,
      boolean favorite,
      boolean excludedFromRadio) {}
}
