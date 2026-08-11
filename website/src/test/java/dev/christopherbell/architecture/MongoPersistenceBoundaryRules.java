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
  private static final String SPRING_MONGO_ROOT = "org.springframework.data.mongodb.";
  private static final String SPRING_CORE_ROOT = SPRING_MONGO_ROOT + "core.";
  private static final Set<String> INERT_DRIVER_VALUE_PACKAGES = Set.of(
      "com.mongodb.client.gridfs.model.",
      "com.mongodb.client.model.",
      "com.mongodb.client.result.");
  private static final Set<String> INERT_DRIVER_VALUE_TYPES = Set.of(
      "com.mongodb.AutoEncryptionSettings",
      "com.mongodb.ClientEncryptionSettings",
      "com.mongodb.ClientSessionOptions",
      "com.mongodb.MongoClientSettings");
  private static final Set<String> INERT_SPRING_VALUE_PACKAGES = Set.of(
      SPRING_CORE_ROOT + "aggregation.",
      SPRING_CORE_ROOT + "geo.",
      SPRING_CORE_ROOT + "mapping.",
      SPRING_CORE_ROOT + "mapreduce.",
      SPRING_CORE_ROOT + "query.",
      SPRING_CORE_ROOT + "schema.",
      SPRING_CORE_ROOT + "time.",
      SPRING_CORE_ROOT + "validation.");
  private static final Set<String> INERT_SPRING_VALUE_TYPES = Set.of(
      SPRING_CORE_ROOT + "ChangeStreamEvent",
      SPRING_CORE_ROOT + "ChangeStreamOptions",
      SPRING_CORE_ROOT + "CollectionOptions",
      SPRING_CORE_ROOT + "FindAndModifyOptions",
      SPRING_CORE_ROOT + "FindAndReplaceOptions",
      SPRING_CORE_ROOT + "ReplaceOptions",
      SPRING_CORE_ROOT + "index.IndexDefinition",
      SPRING_CORE_ROOT + "index.IndexInfo",
      SPRING_MONGO_ROOT + "gridfs.GridFsResource");
  private static final Set<String> SPRING_ACCESS_FACTORIES = Set.of(
      "org.springframework.data.mongodb.core.MongoClientFactoryBean",
      "org.springframework.data.mongodb.core.ReactiveMongoClientFactoryBean",
      "org.springframework.data.mongodb.repository.support.MongoRepositoryFactory",
      "org.springframework.data.mongodb.repository.support.MongoRepositoryFactoryBean",
      "org.springframework.data.mongodb.repository.support.ReactiveMongoRepositoryFactory",
      "org.springframework.data.mongodb.repository.support.ReactiveMongoRepositoryFactoryBean");

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

  static boolean isForbiddenMongoType(JavaClass target) {
    if (target.isAssignableTo(Repository.class)) {
      return true;
    }
    var name = target.getName();
    return isDriverAccessType(name) || isSpringAccessType(name);
  }

  private static boolean isDriverAccessType(String name) {
    if (INERT_DRIVER_VALUE_TYPES.contains(name)
        || startsWithAny(name, INERT_DRIVER_VALUE_PACKAGES)) {
      return false;
    }
    return name.startsWith("com.mongodb.client.")
        || name.startsWith("com.mongodb.session.");
  }

  private static boolean isSpringAccessType(String name) {
    if (INERT_SPRING_VALUE_TYPES.contains(name)
        || startsWithAny(name, INERT_SPRING_VALUE_PACKAGES)) {
      return false;
    }
    var simpleName = name.substring(name.lastIndexOf('.') + 1);
    if (SPRING_ACCESS_FACTORIES.contains(name)
        || name.equals("org.springframework.data.mongodb.MongoDatabaseFactory")
        || name.equals("org.springframework.data.mongodb.ReactiveMongoDatabaseFactory")) {
      return true;
    }
    if (name.startsWith(SPRING_MONGO_ROOT)
        && !name.substring(SPRING_MONGO_ROOT.length()).contains(".")) {
      return simpleName.endsWith("DatabaseUtils")
          || simpleName.endsWith("MongoClusterCapable")
          || simpleName.equals("MongoSessionProvider")
          || simpleName.endsWith("MongoTransactionManager")
          || simpleName.endsWith("MongoResourceHolder")
          || simpleName.equals("SessionAwareMethodInterceptor");
    }
    if (name.startsWith(SPRING_CORE_ROOT + "index.")) {
      return simpleName.contains("IndexOperations")
          || simpleName.endsWith("EntityIndexCreator");
    }
    if (name.startsWith(SPRING_CORE_ROOT + "convert.")) {
      return simpleName.equals("DefaultDbRefResolver")
          || simpleName.equals("MongoDatabaseFactoryReferenceLoader");
    }
    if (name.startsWith(SPRING_MONGO_ROOT + "gridfs.")) {
      return simpleName.contains("GridFsOperations") || simpleName.endsWith("GridFsTemplate");
    }
    if (name.startsWith(SPRING_CORE_ROOT + "messaging.")) {
      return simpleName.endsWith("Task")
          || simpleName.equals("TaskFactory")
          || simpleName.endsWith("MessageListenerContainer")
          || simpleName.equals("Subscription");
    }
    if (name.startsWith(SPRING_MONGO_ROOT + "repository.support.")) {
      return simpleName.contains("RepositoryFactory")
          || simpleName.matches("Simple(Reactive)?MongoRepository")
          || simpleName.contains("MongoPredicateExecutor")
          || simpleName.contains("SpringDataMongodbQuery")
          || simpleName.equals("IndexEnsuringQueryCreationListener")
          || simpleName.contains("RepositoryFragmentsContributor");
    }
    if (!name.startsWith(SPRING_CORE_ROOT)
        || name.substring(SPRING_CORE_ROOT.length()).contains(".")) {
      return false;
    }
    return simpleName.contains("Operations")
        || simpleName.startsWith("Executable")
        || simpleName.startsWith("Reactive") && simpleName.contains("Operation")
        || simpleName.endsWith("MongoTemplate")
        || simpleName.contains("DatabaseFactory")
        || simpleName.equals("MongoAdmin")
        || simpleName.endsWith("CollectionCallback")
        || simpleName.endsWith("DatabaseCallback")
        || simpleName.endsWith("SessionCallback")
        || simpleName.endsWith("SessionScoped")
        || simpleName.endsWith("CursorPreparer")
        || simpleName.endsWith("CollectionPreparer")
        || simpleName.endsWith("BulkWriter")
        || simpleName.endsWith("BulkWriterSupport")
        || simpleName.endsWith("BulkWriteSupport");
  }

  private static boolean startsWithAny(String name, Set<String> prefixes) {
    return prefixes.stream().anyMatch(name::startsWith);
  }
}
