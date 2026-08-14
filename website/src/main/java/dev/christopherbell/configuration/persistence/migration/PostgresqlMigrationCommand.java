package dev.christopherbell.configuration.persistence.migration;

/** Closed set of non-web Mongo-to-PostgreSQL bridge operations. */
public enum PostgresqlMigrationCommand {
  SHADOW("shadow"),
  FINALIZE("finalize"),
  RECONCILE("reconcile"),
  STATUS("status");

  private static final String INVALID = "PostgreSQL migration command is invalid.";

  private final String externalName;

  PostgresqlMigrationCommand(String externalName) {
    this.externalName = externalName;
  }

  /** Parses the exact stable command spelling used by scripts and evidence. */
  public static PostgresqlMigrationCommand parse(String value) {
    for (var command : values()) {
      if (command.externalName.equals(value)) {
        return command;
      }
    }
    throw new IllegalArgumentException(INVALID);
  }

  /** Returns the stable lower-case command spelling. */
  public String externalName() {
    return externalName;
  }
}
