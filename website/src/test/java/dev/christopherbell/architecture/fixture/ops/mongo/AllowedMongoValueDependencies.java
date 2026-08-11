package dev.christopherbell.architecture.fixture.ops.mongo;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.springframework.data.mongodb.core.ChangeStreamEvent;
import org.springframework.data.mongodb.core.ChangeStreamOptions;
import org.springframework.data.mongodb.core.CollectionOptions;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsResource;

/** Inert Mongo values that domain code may use without acquiring database access. */
@SuppressWarnings("unused")
final class AllowedMongoValueDependencies {
  private Filters filters;
  private ChangeStreamDocument<Document> changeStreamDocument;
  private UpdateResult updateResult;
  private ChangeStreamEvent<Document> changeStreamEvent;
  private ChangeStreamOptions changeStreamOptions;
  private CollectionOptions collectionOptions;
  private FindAndModifyOptions findAndModifyOptions;
  private IndexDefinition indexDefinition;
  private IndexInfo indexInfo;
  private MongoMappingContext mappingContext;
  private Criteria criteria;
  private Query query;
  private Update update;
  private GridFsResource gridFsResource;
}
