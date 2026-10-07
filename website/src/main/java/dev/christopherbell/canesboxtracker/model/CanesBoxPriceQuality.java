package dev.christopherbell.canesboxtracker.model;

/** Whether a metro price counts toward the index. The name is the stored {@code qualityStatus}. */
public enum CanesBoxPriceQuality {
  /** Counts toward the public index average. */
  VERIFIED,
  /** Shown but not averaged until an admin approves it. */
  PROVISIONAL,
  /** Shown as excluded and never averaged. */
  EXCLUDED
}
