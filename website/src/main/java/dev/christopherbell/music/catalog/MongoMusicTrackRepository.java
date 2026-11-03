package dev.christopherbell.music.catalog;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Kind-scoped Mongo implementation of the music-track persistence port. */
@MongoPersistence
@Repository
public class MongoMusicTrackRepository extends KindScopedRepositorySupport<MusicTrack>
    implements MusicTrackRepository {
  public MongoMusicTrackRepository(DomainMongoOperationsFactory factory) {
    super(factory, MusicTrack.class);
  }

  @Override
  public MusicTrack save(MusicTrack track) {
    var identity = Query.query(Criteria.where("id").is(track.id()));
    var metadata = new Update()
        .set("path", track.path())
        .set("observedToken", track.observedToken())
        .set("pendingObservedToken", track.pendingObservedToken())
        .set("title", track.title())
        .set("artist", track.artist())
        .set("albumArtist", track.albumArtist())
        .set("album", track.album())
        .set("trackNumber", track.trackNumber())
        .set("discNumber", track.discNumber())
        .set("genre", track.genre())
        .set("year", track.year())
        .set("durationSeconds", track.durationSeconds())
        .set("audioCodec", track.audioCodec())
        .set("container", track.container())
        .set("artworkRevision", track.artworkRevision())
        .set("indexStatus", track.indexStatus())
        .set("indexFailure", track.indexFailure())
        .set("lastProbeAttemptAt", track.lastProbeAttemptAt())
        .set("indexedAt", track.indexedAt())
        .set("missingSince", track.missingSince());
    var refreshed = mongo.findAndUpdate(identity, metadata);
    if (refreshed.isPresent()) {
      return refreshed.orElseThrow();
    }
    try {
      return insertValue(track);
    } catch (DuplicateKeyException concurrentInsert) {
      // Another indexer may have inserted this identity and accepted preferences meanwhile.
      return mongo.findAndUpdate(identity, metadata).orElseThrow(() -> concurrentInsert);
    }
  }
  @Override
  public Optional<MusicTrack> findById(String id) {
    return findValueById(id);
  }
  @Override
  public Optional<MusicTrack> findByPath(String path) {
    return findOne(Query.query(Criteria.where("path").is(path)));
  }
  @Override
  public List<MusicTrack> findAllByMissingSinceIsNull() {
    return find(Query.query(Criteria.where("missingSince").is(null)));
  }
  @Override
  public boolean updatePreferences(
      String id, MusicTrackPreferences expected, MusicTrackPreferences desired) {
    return mongo.updateFirst(
        Query.query(Criteria.where("id").is(id)
            .and("favorite").is(expected.favorite())
            .and("excludedFromRadio").is(expected.excludedFromRadio())),
        new Update()
            .set("favorite", desired.favorite())
            .set("excludedFromRadio", desired.excludedFromRadio()))
        .getMatchedCount() == 1;
  }
}
