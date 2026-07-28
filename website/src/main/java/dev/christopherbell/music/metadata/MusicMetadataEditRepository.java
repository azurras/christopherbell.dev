package dev.christopherbell.music.metadata;

import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MusicMetadataEditRepository extends MongoRepository<MusicMetadataEdit, String> {
  List<MusicMetadataEdit> findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(Instant cutoff);
}
