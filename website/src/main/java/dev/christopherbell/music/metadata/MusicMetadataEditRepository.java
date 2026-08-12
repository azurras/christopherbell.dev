package dev.christopherbell.music.metadata;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for private music metadata edit records. */
public interface MusicMetadataEditRepository {
  MusicMetadataEdit save(MusicMetadataEdit edit);
  Optional<MusicMetadataEdit> findById(String id);
  void deleteById(String id);
  void delete(MusicMetadataEdit edit);
  List<MusicMetadataEdit> findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(Instant cutoff);
}
