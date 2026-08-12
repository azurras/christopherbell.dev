package dev.christopherbell.configuration.mongo.domain;

import static org.mockito.Mockito.when;

import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/** Creates the real kind-scoped boundary over a mocked MongoTemplate for adapter contract tests. */
public final class DomainMongoOperationsTestFactory {
  private DomainMongoOperationsTestFactory() {}

  public static DomainMongoOperationsFactory create(MongoTemplate mongo) {
    try {
      var context = new MongoMappingContext();
      context.setInitialEntitySet(Set.of());
      context.setSimpleTypeHolder(
          MongoCustomConversions.create(adapter -> {}).getSimpleTypeHolder());
      context.afterPropertiesSet();
      var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
      converter.setTypeMapper(new DefaultMongoTypeMapper(null));
      converter.afterPropertiesSet();
      when(mongo.getConverter()).thenReturn(converter);
      return new DomainMongoOperationsFactory(mongo);
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot create domain Mongo test boundary.", failure);
    }
  }

  /** Creates the real boundary over a disposable MongoTemplate. */
  public static DomainMongoOperationsFactory createForDisposableMongo(MongoTemplate mongo) {
    return new DomainMongoOperationsFactory(mongo);
  }

  /** Encodes one test value exactly as the real boundary expects from MongoTemplate. */
  @SuppressWarnings("unchecked")
  public static <T> Document envelope(MongoTemplate mongo, T value) {
    var javaType = (Class<T>) value.getClass();
    var kind = DomainCollectionManifest.forType(javaType);
    var payload = new Document();
    mongo.getConverter().write(value, payload);
    var legacyId = payload.remove("_id");
    return new Document("_id", NamespacedMongoId.of(kind.kind(), legacyId).toBson())
        .append("_kind", kind.kind())
        .append("schemaVersion", kind.schemaVersion())
        .append("payload", payload);
  }

  /** Maps one aggregation result through the same converter used by production. */
  public static Document mappedDocument(MongoTemplate mongo, Object value) {
    var mapped = new Document();
    mongo.getConverter().write(value, mapped);
    mapped.remove("_class");
    return mapped;
  }
}
