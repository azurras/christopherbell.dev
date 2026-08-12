package dev.christopherbell.music.library;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Kind-scoped Mongo implementation of the music-playlist persistence port. */
@Repository
public final class MongoMusicPlaylistRepository extends KindScopedRepositorySupport<MusicPlaylist>
    implements MusicPlaylistRepository {
  public MongoMusicPlaylistRepository(DomainMongoOperationsFactory factory) {
    super(factory, MusicPlaylist.class);
  }

  @Override public MusicPlaylist save(MusicPlaylist playlist) { return saveValue(playlist); }
  @Override public Optional<MusicPlaylist> findById(String id) { return findValueById(id); }
  @Override public List<MusicPlaylist> findTop100ByOrderByNormalizedNameAsc() {
    return find(new Query().with(Sort.by("normalizedName")), PageRequest.of(0, 100));
  }
  @Override public long count() { return mongo.count(new Query()); }
  @Override public void delete(MusicPlaylist playlist) {
    var query = Query.query(Criteria.where("id").is(playlist.id()));
    if (playlist.version() != null) query.addCriteria(Criteria.where("version").is(playlist.version()));
    if (mongo.remove(query).getDeletedCount() != 1) {
      throw new OptimisticLockingFailureException("Music playlist changed during deletion.");
    }
  }
}
