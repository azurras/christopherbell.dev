package dev.christopherbell.architecture.fixture.ops.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ForbiddenSpringRepository extends MongoRepository<ForbiddenStoredValue, String> {}
