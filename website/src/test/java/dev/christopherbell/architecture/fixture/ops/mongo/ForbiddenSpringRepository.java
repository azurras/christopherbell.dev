package dev.christopherbell.architecture.fixture.ops.mongo;

import org.springframework.data.repository.CrudRepository;

public interface ForbiddenSpringRepository extends CrudRepository<ForbiddenStoredValue, String> {}
