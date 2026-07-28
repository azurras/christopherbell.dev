package dev.christopherbell.music.radio;

import dev.christopherbell.music.catalog.MusicTrack;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.IntUnaryOperator;

/** Pure smart-radio policy with deterministic randomness injected at the boundary. */
public final class MusicRadioSelector {
  private final MusicRadioProperties properties;
  private final DoubleSupplier exploration;
  private final IntUnaryOperator randomIndex;

  public MusicRadioSelector(
      MusicRadioProperties properties,
      DoubleSupplier exploration,
      IntUnaryOperator randomIndex) {
    this.properties = properties;
    this.exploration = exploration;
    this.randomIndex = randomIndex;
  }

  public MusicTrack select(
      List<MusicTrack> candidates,
      List<MusicRadioHistoryEvent> recentHistory,
      String previousTrackId) {
    List<MusicTrack> available = candidates.stream()
        .filter(track -> track != null && !track.excludedFromRadio())
        .toList();
    if (available.isEmpty()) {
      throw new IllegalStateException("Music radio has no eligible tracks.");
    }
    List<MusicTrack> withoutImmediateRepeat = available.size() > 1 && previousTrackId != null
        ? available.stream().filter(track -> !previousTrackId.equals(track.id())).toList()
        : available;
    List<MusicTrack> cooledTracks = filterTrackCooldown(withoutImmediateRepeat, recentHistory);
    List<MusicTrack> cooledArtists = filterArtistCooldown(cooledTracks, recentHistory);
    List<MusicTrack> pool = cooledArtists.isEmpty()
        ? cooledTracks.isEmpty() ? withoutImmediateRepeat : cooledTracks
        : cooledArtists;
    if (exploration.getAsDouble() < properties.explorationProbability()) {
      return pool.get(boundedRandomIndex(pool.size()));
    }
    var lastHeard = new HashMap<String, Long>();
    for (MusicRadioHistoryEvent event : recentHistory) {
      if (event.outcome() == MusicRadioHistoryEvent.Outcome.PLAYED) {
        lastHeard.putIfAbsent(event.trackId(), event.stationSequence());
      }
    }
    return pool.stream().min(Comparator
        .comparingLong((MusicTrack track) -> weightedLastHeard(track, lastHeard))
        .thenComparing(MusicTrack::id)).orElseThrow();
  }

  private List<MusicTrack> filterTrackCooldown(
      List<MusicTrack> tracks,
      List<MusicRadioHistoryEvent> history) {
    Set<String> recentIds = history.stream()
        .filter(event -> event.outcome() == MusicRadioHistoryEvent.Outcome.PLAYED)
        .limit(properties.trackCooldown())
        .map(MusicRadioHistoryEvent::trackId)
        .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    List<MusicTrack> filtered = tracks.stream()
        .filter(track -> !recentIds.contains(track.id())).toList();
    return filtered.isEmpty() ? tracks : filtered;
  }

  private List<MusicTrack> filterArtistCooldown(
      List<MusicTrack> tracks,
      List<MusicRadioHistoryEvent> history) {
    if (properties.artistCooldown() == 0) {
      return tracks;
    }
    Set<String> recentArtists = history.stream()
        .filter(event -> event.outcome() == MusicRadioHistoryEvent.Outcome.PLAYED)
        .limit(properties.artistCooldown())
        .map(MusicRadioHistoryEvent::artist)
        .filter(java.util.Objects::nonNull)
        .map(this::normalizeArtist)
        .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    List<MusicTrack> filtered = tracks.stream()
        .filter(track -> !recentArtists.contains(normalizeArtist(track.artist()))).toList();
    return filtered.isEmpty() ? tracks : filtered;
  }

  private long weightedLastHeard(MusicTrack track, HashMap<String, Long> lastHeard) {
    Long sequence = lastHeard.get(track.id());
    if (sequence == null) {
      return Long.MIN_VALUE + (track.favorite() ? 0 : 1);
    }
    return sequence - (track.favorite() ? 5 : 0);
  }

  private String normalizeArtist(String artist) {
    return artist == null ? "" : artist.strip().toLowerCase(Locale.ROOT);
  }

  private int boundedRandomIndex(int bound) {
    int selected = randomIndex.applyAsInt(bound);
    if (selected < 0 || selected >= bound) {
      throw new IllegalStateException("Music radio random index is outside its requested bound.");
    }
    return selected;
  }
}
