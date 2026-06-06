package dev.christopherbell.canesboxtracker;

import dev.christopherbell.canesboxtracker.model.CanesBoxMetroPrice;
import dev.christopherbell.canesboxtracker.model.CanesBoxTrackerProperties;

/**
 * Fetches Raising Canes Box Combo prices for configured restaurant targets.
 */
@FunctionalInterface
public interface CanesBoxPriceClient {
  /**
   * Fetches the current Box Combo price for one metro target.
   *
   * @param target configured metro/store target
   * @return successful or failed metro price result
   */
  CanesBoxMetroPrice fetchBoxComboPrice(CanesBoxTrackerProperties.MetroTarget target);
}
