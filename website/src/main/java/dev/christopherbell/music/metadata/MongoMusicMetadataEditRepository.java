package dev.christopherbell.music.metadata;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Kind-scoped Mongo implementation of the music metadata-edit persistence port. */
@Repository
public class MongoMusicMetadataEditRepository
    extends KindScopedRepositorySupport<MusicMetadataEdit>
    implements MusicMetadataEditRepository {
  public MongoMusicMetadataEditRepository(DomainMongoOperationsFactory factory) {
    super(factory, MusicMetadataEdit.class);
  }

  @Override public MusicMetadataEdit save(MusicMetadataEdit edit) { return saveValue(edit); }
  @Override public Optional<MusicMetadataEdit> findById(String id) { return findValueById(id); }
  @Override public void deleteById(String id) { super.deleteById(id); }
  @Override public void delete(MusicMetadataEdit edit) {
    var query = Query.query(Criteria.where("id").is(edit.id()));
    if (edit.version() != null) query.addCriteria(Criteria.where("version").is(edit.version()));
    if (mongo.remove(query).getDeletedCount() != 1) {
      throw new OptimisticLockingFailureException("Music metadata edit changed during deletion.");
    }
  }
  @Override public List<MusicMetadataEdit> findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(
      Instant cutoff) {
    return find(Query.query(Criteria.where("expiresAt").lt(cutoff))
        .with(Sort.by(Sort.Direction.ASC, "expiresAt")), PageRequest.of(0, 100));
  }
}
