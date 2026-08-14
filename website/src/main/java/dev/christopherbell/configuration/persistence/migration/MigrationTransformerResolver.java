package dev.christopherbell.configuration.persistence.migration;

@FunctionalInterface
public interface MigrationTransformerResolver {
  MigrationTransformer require(String sourceKind);
}
