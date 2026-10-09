package dev.christopherbell.account.model.dto;

/** A requested read and write state for one stored account capability pair. */
public interface CapabilityPairUpdate {
  Boolean read();

  Boolean write();
}
