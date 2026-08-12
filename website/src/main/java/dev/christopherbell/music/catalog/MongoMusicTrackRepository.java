package dev.christopherbell.music.catalog;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Kind-scoped Mongo implementation of the music-track persistence port. */
@Repository
public final class MongoMusicTrackRepository extends KindScopedRepositorySupport<MusicTrack>
    implements MusicTrackRepository {
  public MongoMusicTrackRepository(DomainMongoOperationsFactory factory) {
    super(factory, MusicTrack.class);
  }

  @Override public MusicTrack save(MusicTrack track) { return saveValue(track); }
  @Override public Optional<MusicTrack> findById(String id) { return findValueById(id); }
  @Override public Optional<MusicTrack> findByPath(String path) {
    return findOne(Query.query(Criteria.where("path").is(path)));
  }
  @Override public List<MusicTrack> findAllByMissingSinceIsNull() {
    return find(Query.query(Criteria.where("missingSince").is(null)));
  }
  @Override public boolean updatePreferences(
      String id,
      boolean expectedFavorite,
      boolean expectedExcluded,
      boolean favorite,
      boolean excluded) {
    return mongo.updateFirst(
        Query.query(Criteria.where("id").is(id)
            .and("favorite").is(expectedFavorite)
            .and("excludedFromRadio").is(expectedExcluded)),
        new Update().set("favorite", favorite).set("excludedFromRadio", excluded))
        .getMatchedCount() == 1;
  }
}
