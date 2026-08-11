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
  private static final Set<String> FORBIDDEN_MONGO_TYPES = Set.of(
      "com.mongodb.client.MongoClient",
      "com.mongodb.client.MongoCollection",
      "com.mongodb.client.MongoDatabase",
      "org.springframework.data.mongodb.MongoDatabaseFactory",
      "org.springframework.data.mongodb.core.MongoOperations",
      "org.springframework.data.mongodb.core.MongoTemplate",
      "org.springframework.data.mongodb.core.ReactiveMongoTemplate");

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
    return FORBIDDEN_MONGO_TYPES.contains(target.getName())
        || target.isAssignableTo(Repository.class);
  }
}
