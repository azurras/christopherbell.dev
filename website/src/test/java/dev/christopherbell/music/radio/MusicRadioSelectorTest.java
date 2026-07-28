package dev.christopherbell.music.radio;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.music.catalog.MusicProbeResult;
import dev.christopherbell.music.catalog.MusicTrack;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MusicRadioSelectorTest {

  @Test
  void avoidsImmediateTrackAndArtistRepeatsWhenAlternativesExist() {
    MusicTrack a = track("a.mp3", "Artist One", false);
    MusicTrack b = track("b.mp3", "Artist One", false);
    MusicTrack c = track("c.mp3", "Artist Two", false);
    var history = List.of(played(10, a));
    var selector = selector(0, 0);

    MusicTrack selected = selector.select(List.of(a, b, c), history, a.id());

    assertThat(selected.id()).isEqualTo(c.id());
  }

  @Test
  void leastRecentlyHeardPolicyIncludesABoundedFavoriteWeight() {
    MusicTrack ordinary = track("ordinary.mp3", "One", false);
    MusicTrack favorite = track("favorite.mp3", "Two", true);
    var history = List.of(played(11, favorite), played(10, ordinary));
    var selector = selector(0, 0);

    assertThat(selector.select(List.of(ordinary, favorite), history, null).id())
        .isEqualTo(favorite.id());
  }

  @Test
  void explorationUsesInjectedBoundedRandomChoice() {
    MusicTrack a = track("a.mp3", "One", false);
    MusicTrack b = track("b.mp3", "Two", false);
    var selector = new MusicRadioSelector(properties(1.0), () -> 0.0, bound -> 1);

    assertThat(selector.select(List.of(a, b), List.of(), null).id()).isEqualTo(b.id());
  }

  private MusicRadioSelector selector(double exploration, int randomIndex) {
    return new MusicRadioSelector(properties(exploration), () -> 1.0, bound -> randomIndex);
  }

  private MusicRadioProperties properties(double exploration) {
    return new MusicRadioProperties(50, 10, exploration, 100, Duration.ofSeconds(10));
  }

  private MusicTrack track(String path, String artist, boolean favorite) {
    MusicTrack initial = MusicTrack.ready(path, "token-" + path, new MusicProbeResult(
        path, artist, artist, "Album", 1, 1, "Genre", 2026,
        10, "mp3", "mp3", false), null, Instant.EPOCH);
    return new MusicTrack(
        initial.id(), initial.path(), initial.observedToken(), initial.pendingObservedToken(),
        initial.title(), initial.artist(), initial.albumArtist(), initial.album(),
        initial.trackNumber(), initial.discNumber(), initial.genre(), initial.year(),
        initial.durationSeconds(), initial.audioCodec(), initial.container(),
        initial.artworkRevision(), favorite, false, initial.indexStatus(), initial.indexFailure(),
        initial.lastProbeAttemptAt(), initial.indexedAt(), initial.missingSince());
  }

  private MusicRadioHistoryEvent played(long sequence, MusicTrack track) {
    return new MusicRadioHistoryEvent(
        "station:" + sequence, sequence, track.id(), track.observedToken(), track.artist(),
        MusicRadioState.Source.RADIO, MusicRadioHistoryEvent.Outcome.PLAYED, Instant.EPOCH);
  }
}
