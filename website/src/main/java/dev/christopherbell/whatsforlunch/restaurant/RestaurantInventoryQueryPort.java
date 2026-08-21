package dev.christopherbell.whatsforlunch.restaurant;

/** Persistence-neutral bounded restaurant inventory query. */
public interface RestaurantInventoryQueryPort {
  RestaurantInventoryQueryRepository.Page find(
      String name, String city, String state, String cursor, int size);
}
