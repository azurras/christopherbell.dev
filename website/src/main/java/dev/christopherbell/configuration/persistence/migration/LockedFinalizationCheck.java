package dev.christopherbell.configuration.persistence.migration;

/** Revalidates frozen-source authority inside the locked PostgreSQL finalization boundary. */
@FunctionalInterface
public interface LockedFinalizationCheck {
  FrozenSourceEvidence revalidate();
}
