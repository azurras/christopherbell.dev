package dev.christopherbell.whatsforlunch.restaurant;

/** Persistence-neutral duplicate restaurant discovery query. */
public interface RestaurantDuplicateQueryPort {
  RestaurantDuplicateQueryRepository.Page find(String cursor, int size);
}
