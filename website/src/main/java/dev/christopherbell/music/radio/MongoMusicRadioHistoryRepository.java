package dev.christopherbell.music.radio;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Kind-scoped Mongo implementation of the music-radio history persistence port. */
@Repository
public final class MongoMusicRadioHistoryRepository
    extends KindScopedRepositorySupport<MusicRadioHistoryEvent>
    implements MusicRadioHistoryRepository {
  public MongoMusicRadioHistoryRepository(DomainMongoOperationsFactory factory) {
    super(factory, MusicRadioHistoryEvent.class);
  }

  @Override public MusicRadioHistoryEvent save(MusicRadioHistoryEvent event) {
    return saveValue(event);
  }
  @Override public boolean existsById(String id) {
    return mongo.exists(Query.query(Criteria.where("id").is(id)));
  }
  @Override public List<MusicRadioHistoryEvent> findTop100ByOrderByStationSequenceDesc() {
    return find(new Query().with(Sort.by(Sort.Direction.DESC, "stationSequence")),
        PageRequest.of(0, 100));
  }
}
