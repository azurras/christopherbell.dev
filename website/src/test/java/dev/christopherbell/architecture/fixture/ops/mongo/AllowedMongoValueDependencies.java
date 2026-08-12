package dev.christopherbell.architecture.fixture.ops.mongo;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.springframework.data.mongodb.MongoExpression;
import org.springframework.data.mongodb.core.ChangeStreamEvent;
import org.springframework.data.mongodb.core.ChangeStreamOptions;
import org.springframework.data.mongodb.core.CollectionOptions;
import org.springframework.data.mongodb.core.DocumentCallbackHandler;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoAction;
import org.springframework.data.mongodb.core.encryption.EncryptionOptions;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.messaging.Message;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.gridfs.GridFsCriteria;

/** Inert Mongo values that domain code may use without acquiring database access. */
@SuppressWarnings("unused")
@EnableMongoAuditing
final class AllowedMongoValueDependencies {
  private Filters filters;
  private ChangeStreamDocument<Document> changeStreamDocument;
  private UpdateResult updateResult;
  private ChangeStreamEvent<Document> changeStreamEvent;
  private ChangeStreamOptions changeStreamOptions;
  private CollectionOptions collectionOptions;
  private DocumentCallbackHandler documentCallbackHandler;
  private FindAndModifyOptions findAndModifyOptions;
  private MongoAction mongoAction;
  private MongoExpression mongoExpression;
  private EncryptionOptions encryptionOptions;
  private IndexDefinition indexDefinition;
  private IndexInfo indexInfo;
  private MongoMappingContext mappingContext;
  private Message<?, ?> message;
  private Criteria criteria;
  private Query query;
  private Update update;
  private GridFsCriteria gridFsCriteria;
}
