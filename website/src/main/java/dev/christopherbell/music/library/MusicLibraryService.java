package dev.christopherbell.music.library;

import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.catalog.MusicTrackRepository;
import dev.christopherbell.music.radio.MusicRadioHistoryEvent;
import dev.christopherbell.music.radio.MusicRadioHistoryRepository;
import dev.christopherbell.music.security.MusicAccessService;
import dev.christopherbell.music.web.MusicTrackView;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Owns global playlists, exact-state preferences, and bounded shared history reads. */
@Service
public final class MusicLibraryService {
  private final MusicPlaylistRepository playlists;
  private final MusicCatalog catalog;
  private final MusicRadioHistoryRepository history;
  private final MusicAccessService access;
  private final MusicTrackRepository tracks;
  private final Clock clock;

  public MusicLibraryService(
      MusicPlaylistRepository playlists,
      MusicCatalog catalog,
      MusicRadioHistoryRepository history,
      MusicAccessService access,
      MusicTrackRepository tracks,
      Clock clock) {
    this.playlists = playlists;
    this.catalog = catalog;
    this.history = history;
    this.access = access;
    this.tracks = tracks;
    this.clock = clock;
  }

  public List<MusicPlaylistView> playlists() {
    access.requireRead();
    return playlists.findTop100ByOrderByNormalizedNameAsc().stream()
        .map(MusicPlaylistView::from).toList();
  }

  /** Resolves one authorized global playlist for server-side catalog paging. */
  public List<String> playlistTrackIds(String id) {
    access.requireRead();
    return playlist(id).trackIds();
  }

  public MusicPlaylistView create(String name, List<String> trackIds) {
    var account = access.requireWrite();
    if (playlists.count() >= 100) {
      throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, "Music playlist limit reached.");
    }
    String safeName = safeName(name);
    List<String> safeTracks = safeTracks(trackIds);
    MusicPlaylist created = new MusicPlaylist(
        UUID.randomUUID().toString(), normalize(safeName), safeName, safeTracks, null,
        account.getId(), clock.instant());
    return MusicPlaylistView.from(save(created));
  }

  public MusicPlaylistView update(
      String id,
      long expectedVersion,
      String name,
      List<String> trackIds) {
    var account = access.requireWrite();
    MusicPlaylist current = exactPlaylist(id, expectedVersion);
    String safeName = safeName(name);
    MusicPlaylist replacement = new MusicPlaylist(
        current.id(), normalize(safeName), safeName, safeTracks(trackIds),
        current.version(), account.getId(), clock.instant());
    return MusicPlaylistView.from(save(replacement));
  }

  public void delete(String id, long expectedVersion) {
    access.requireWrite();
    MusicPlaylist current = exactPlaylist(id, expectedVersion);
    try {
      playlists.delete(current);
    } catch (OptimisticLockingFailureException contention) {
      throw conflict();
    }
  }

  public MusicTrackView updatePreferences(
      String trackId,
      boolean expectedFavorite,
      boolean expectedExcluded,
      boolean favorite,
      boolean excluded) {
    access.requireWrite();
    MusicTrack current = catalog.findReady(trackId).orElseThrow(this::trackNotFound);
    if (!tracks.updatePreferences(
        current.id(), expectedFavorite, expectedExcluded, favorite, excluded)) {
      if (catalog.findReady(trackId).isEmpty()) {
        throw trackNotFound();
      }
      throw conflict();
    }
    return catalog.findReady(trackId).map(MusicTrackView::from).orElseThrow(this::trackNotFound);
  }

  public List<MusicRadioHistoryEvent> history(int requestedLimit) {
    access.requireRead();
    int limit = Math.max(1, Math.min(100, requestedLimit));
    return history.findTop100ByOrderByStationSequenceDesc().stream().limit(limit).toList();
  }

  private MusicPlaylist exactPlaylist(String id, long expectedVersion) {
    MusicPlaylist current = playlist(id);
    if (current.publicVersion() != expectedVersion) {
      throw conflict();
    }
    return current;
  }

  private MusicPlaylist playlist(String id) {
    return playlists.findById(id).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "Music playlist was not found."));
  }

  private MusicPlaylist save(MusicPlaylist playlist) {
    try {
      return playlists.save(playlist);
    } catch (DuplicateKeyException duplicate) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Music playlist name already exists.");
    } catch (OptimisticLockingFailureException contention) {
      throw conflict();
    }
  }

  private List<String> safeTracks(List<String> trackIds) {
    List<String> safe = trackIds == null ? List.of() : List.copyOf(trackIds);
    if (safe.size() > 1_000 || new HashSet<>(safe).size() != safe.size()
        || safe.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 128)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Music playlist tracks are invalid.");
    }
    if (safe.stream().anyMatch(id -> catalog.findReady(id).isEmpty())) {
      throw trackNotFound();
    }
    return safe;
  }

  private String safeName(String name) {
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Music playlist name is required.");
    }
    String safe = name.strip().replaceAll("[\\p{Cc}\\p{Cf}]", "");
    if (safe.isBlank() || safe.length() > 100) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Music playlist name is invalid.");
    }
    return safe;
  }

  private String normalize(String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Music library changed. Refresh and retry.");
  }

  private ResponseStatusException trackNotFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Music track was not found.");
  }
}
