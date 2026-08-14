package dev.christopherbell.music.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

/** Deterministic result shaping shared by persistence-engine query adapters. */
final class MusicCatalogResultSupport {
  private MusicCatalogResultSupport() {}

  static List<MusicAlbumGroup> albums(List<MusicTrack> matches) {
    var groups = new LinkedHashMap<String, ArrayList<MusicTrack>>();
    for (MusicTrack track : matches) {
      String artist = safe(track.albumArtist(), track.artist());
      String key = artist + '\n' + safe(track.album(), "Unknown Album");
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(track);
    }
    return groups.entrySet().stream().map(entry -> {
      String[] key = entry.getKey().split("\\n", 2);
      return new MusicAlbumGroup(key[0], key[1], List.copyOf(entry.getValue()));
    }).toList();
  }

  static List<String> strings(List<String> values) {
    return values.stream().filter(value -> value != null && !value.isBlank())
        .collect(java.util.stream.Collectors.collectingAndThen(
            java.util.stream.Collectors.toCollection(() -> new TreeSet<>(
                Comparator.comparing(value -> value.toLowerCase(java.util.Locale.ROOT)))),
            List::copyOf));
  }

  static int totalPages(long totalTracks, int size) {
    long pages = totalTracks / size + (totalTracks % size == 0 ? 0 : 1);
    return Math.toIntExact(pages);
  }

  private static String safe(String preferred, String fallback) {
    return preferred == null || preferred.isBlank() ? fallback : preferred;
  }
}
