package dev.christopherbell.music.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence boundary for indexed Music tracks. */
public interface MusicTrackRepository extends MongoRepository<MusicTrack, String> {
  Optional<MusicTrack> findByPath(String path);

  List<MusicTrack> findAllByMissingSinceIsNull();
}
