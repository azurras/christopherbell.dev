package dev.christopherbell.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

final class LegacyModuleDependencyRules {
  private static final String APPLICATION_ROOT = "dev.christopherbell";
  private static final Set<String> APPLICATION_AREAS = Set.of(
      "account",
      "admin",
      "blog",
      "canesboxtracker",
      "configuration",
      "federation",
      "location",
      "message",
      "music",
      "notification",
      "permission",
      "photo",
      "post",
      "report",
      "sharedfolder",
      "vehicle",
      "view",
      "whatsforlunch");
  private static final Set<String> ORCHESTRATION_AREAS =
      Set.of("admin", "configuration", "view");

  private final String rootPackage;
  private final Set<String> applicationAreas;
  private final Set<String> orchestrationAreas;
  private final Set<String> externalAreas;

  LegacyModuleDependencyRules(
      String rootPackage,
      Set<String> applicationAreas,
      Set<String> orchestrationAreas) {
    this(rootPackage, applicationAreas, orchestrationAreas, Set.of());
  }

  private LegacyModuleDependencyRules(
      String rootPackage,
      Set<String> applicationAreas,
      Set<String> orchestrationAreas,
      Set<String> externalAreas) {
    this.rootPackage = rootPackage;
    this.applicationAreas = Set.copyOf(applicationAreas);
    this.orchestrationAreas = Set.copyOf(orchestrationAreas);
    this.externalAreas = Set.copyOf(externalAreas);
  }

  static LegacyModuleDependencyRules production() {
    return new LegacyModuleDependencyRules(
        APPLICATION_ROOT, APPLICATION_AREAS, ORCHESTRATION_AREAS, Set.of("libs"));
  }

  JavaClasses importProductionClasses() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(rootPackage);
  }

  Set<String> unknownAreas(JavaClasses classes) {
    var unknown = new TreeSet<String>();
    classes.stream()
        .map(JavaClass::getPackageName)
        .map(this::firstAreaOf)
        .flatMap(Optional::stream)
        .filter(area -> !applicationAreas.contains(area))
        .filter(area -> !externalAreas.contains(area))
        .forEach(unknown::add);
    return Set.copyOf(unknown);
  }

  ArchRule crossAreaAccessRule() {
    return classes()
        .that().resideInAPackage(rootPackage + "..")
        .should(new CrossAreaAccessCondition(ViolationKind.INTERNAL_ACCESS));
  }

  ArchRule frozenCrossAreaAccessRule() {
    return FreezingArchRule.freeze(crossAreaAccessRule());
  }

  ArchRule orchestrationDirectionRule() {
    return classes()
        .that().resideInAPackage(rootPackage + "..")
        .should(new CrossAreaAccessCondition(ViolationKind.ORCHESTRATION_DIRECTION));
  }

  ArchRule frozenOrchestrationDirectionRule() {
    return FreezingArchRule.freeze(orchestrationDirectionRule());
  }

  Optional<String> areaOf(String packageName) {
    return physicalAreaOf(packageName).map(area -> area.equals("permission") ? "account" : area);
  }

  private Optional<String> physicalAreaOf(String packageName) {
    return firstAreaOf(packageName).filter(applicationAreas::contains);
  }

  private Optional<String> firstAreaOf(String packageName) {
    var prefix = rootPackage + ".";
    if (!packageName.startsWith(prefix)) {
      return Optional.empty();
    }

    var remainder = packageName.substring(prefix.length());
    var separator = remainder.indexOf('.');
    var area = separator < 0 ? remainder : remainder.substring(0, separator);
    return Optional.of(area);
  }

  private boolean isPublishedApi(String packageName) {
    var physicalArea = physicalAreaOf(packageName);
    if (physicalArea.isEmpty() || physicalArea.get().equals("permission")) {
      return false;
    }

    var apiPackage = rootPackage + "." + physicalArea.get() + ".api";
    return packageName.equals(apiPackage) || packageName.startsWith(apiPackage + ".");
  }

  private Optional<AccessViolation> violation(Dependency dependency, ViolationKind kind) {
    var source = dependency.getOriginClass();
    var target = dependency.getTargetClass();
    var sourceArea = areaOf(source.getPackageName());
    var targetArea = areaOf(target.getPackageName());

    if (sourceArea.isEmpty() || targetArea.isEmpty() || sourceArea.equals(targetArea)) {
      return Optional.empty();
    }

    if (kind == ViolationKind.INTERNAL_ACCESS && isPublishedApi(target.getPackageName())) {
      return Optional.empty();
    }

    if (kind == ViolationKind.ORCHESTRATION_DIRECTION
        && (orchestrationAreas.contains(sourceArea.get())
            || !orchestrationAreas.contains(targetArea.get()))) {
      return Optional.empty();
    }

    return Optional.of(new AccessViolation(
        sourceArea.get(), targetArea.get(), source.getName(), target.getName()));
  }

  private enum ViolationKind {
    INTERNAL_ACCESS,
    ORCHESTRATION_DIRECTION
  }

  private record AccessViolation(
      String sourceArea,
      String targetArea,
      String sourceClass,
      String targetClass) implements Comparable<AccessViolation> {

    String description() {
      return "%s -> %s | %s -> %s"
          .formatted(sourceArea, targetArea, sourceClass, targetClass);
    }

    @Override
    public int compareTo(AccessViolation other) {
      return description().compareTo(other.description());
    }
  }

  private final class CrossAreaAccessCondition extends ArchCondition<JavaClass> {
    private final ViolationKind kind;

    private CrossAreaAccessCondition(ViolationKind kind) {
      super(kind == ViolationKind.INTERNAL_ACCESS
          ? "access only its own area or another area's published api"
          : "not depend on an orchestration area from a business area");
      this.kind = kind;
    }

    @Override
    public void check(JavaClass source, ConditionEvents events) {
      source.getDirectDependenciesFromSelf().stream()
          .map(dependency -> violation(dependency, kind))
          .flatMap(Optional::stream)
          .distinct()
          .sorted()
          .forEach(violation -> events.add(
              SimpleConditionEvent.violated(source, violation.description())));
    }
  }
}
