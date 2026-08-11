package dev.christopherbell.architecture.fixture.ops.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;

@SuppressWarnings("unused")
public final class ForbiddenMongoApiDependencies {
  private MongoOperations operations;
  private MongoTemplate template;
  private ReactiveMongoTemplate reactiveTemplate;
  private MongoClient client;
  private MongoDatabase database;
  private MongoDatabaseFactory databaseFactory;
  private MongoCollection<Document> collection;
}
