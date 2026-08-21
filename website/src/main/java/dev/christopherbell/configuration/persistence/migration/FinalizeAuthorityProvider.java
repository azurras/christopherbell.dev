package dev.christopherbell.configuration.persistence.migration;

/** Reloads signed frozen-source authority from its protected external trust root. */
@FunctionalInterface
public interface FinalizeAuthorityProvider {
  FrozenSourceEvidence reload(FrozenSourceEvidence expected);
}
