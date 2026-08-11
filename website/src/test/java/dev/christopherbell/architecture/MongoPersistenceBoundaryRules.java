package dev.christopherbell.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.repository.Repository;

/** Compiled dependency rule for the final manifest-backed Mongo boundary. */
final class MongoPersistenceBoundaryRules {
  private static final Set<String> FORBIDDEN_ACCESS_INTERFACES = Set.of(
      "com.mongodb.client.MongoClient",
      "com.mongodb.client.MongoCollection",
      "com.mongodb.client.MongoDatabase",
      "org.springframework.data.mongodb.MongoDatabaseFactory",
      "org.springframework.data.mongodb.ReactiveMongoDatabaseFactory",
      "org.springframework.data.mongodb.core.ExecutableAggregationOperation",
      "org.springframework.data.mongodb.core.ExecutableFindOperation",
      "org.springframework.data.mongodb.core.ExecutableInsertOperation",
      "org.springframework.data.mongodb.core.ExecutableMapReduceOperation",
      "org.springframework.data.mongodb.core.ExecutableRemoveOperation",
      "org.springframework.data.mongodb.core.ExecutableUpdateOperation",
      "org.springframework.data.mongodb.core.FluentMongoOperations",
      "org.springframework.data.mongodb.core.MongoOperations",
      "org.springframework.data.mongodb.core.ReactiveAggregationOperation",
      "org.springframework.data.mongodb.core.ReactiveChangeStreamOperation",
      "org.springframework.data.mongodb.core.ReactiveFindOperation",
      "org.springframework.data.mongodb.core.ReactiveFluentMongoOperations",
      "org.springframework.data.mongodb.core.ReactiveInsertOperation",
      "org.springframework.data.mongodb.core.ReactiveMapReduceOperation",
      "org.springframework.data.mongodb.core.ReactiveMongoOperations",
      "org.springframework.data.mongodb.core.ReactiveRemoveOperation",
      "org.springframework.data.mongodb.core.ReactiveUpdateOperation");
  private static final Set<String> FORBIDDEN_ACCESS_FACTORIES = Set.of(
      "com.mongodb.client.MongoClientFactory",
      "com.mongodb.client.MongoClients",
      "org.springframework.data.mongodb.core.MongoClientFactoryBean",
      "org.springframework.data.mongodb.core.ReactiveMongoClientFactoryBean");

  private final String rootPackage;
  private final Set<String> approvedOwners;

  MongoPersistenceBoundaryRules(String rootPackage, Set<String> approvedOwners) {
    this.rootPackage = rootPackage;
    this.approvedOwners = Set.copyOf(approvedOwners);
  }

  ArchRule zeroBypassRule() {
    return classes()
        .that().resideInAPackage(rootPackage + "..")
        .should(new NoDirectMongoDependencyCondition());
  }

  private final class NoDirectMongoDependencyCondition extends ArchCondition<JavaClass> {
    private NoDirectMongoDependencyCondition() {
      super("depend on Mongo only through an approved kind-scoped infrastructure owner");
    }

    @Override
    public void check(JavaClass source, ConditionEvents events) {
      if (approvedOwners.contains(source.getName())) {
        return;
      }
      var violations = new TreeSet<String>();
      source.getDirectDependenciesFromSelf().stream()
          .map(dependency -> dependency.getTargetClass())
          .filter(MongoPersistenceBoundaryRules::isForbiddenMongoType)
          .map(JavaClass::getName)
          .forEach(violations::add);
      if (source.isAssignableTo(Repository.class)) {
        violations.add(Repository.class.getName());
      }
      violations.forEach(target -> events.add(SimpleConditionEvent.violated(
          source, source.getName() + " directly depends on " + target)));
    }
  }

  private static boolean isForbiddenMongoType(JavaClass target) {
    return FORBIDDEN_ACCESS_FACTORIES.contains(target.getName())
        || FORBIDDEN_ACCESS_INTERFACES.stream().anyMatch(target::isAssignableTo)
        || target.isAssignableTo(Repository.class);
  }
}
