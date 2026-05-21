package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Stores member favorite restaurants for What's For Lunch. */
@Repository
public interface RestaurantFavoriteRepository extends MongoRepository<RestaurantFavorite, String> {
  void deleteByRestaurantIdAndAccountId(String restaurantId, String accountId);

  List<RestaurantFavorite> findByAccountIdOrderByCreatedOnDesc(String accountId);

  List<RestaurantFavorite> findByRestaurantIdInAndAccountId(Collection<String> restaurantIds, String accountId);

  Optional<RestaurantFavorite> findByRestaurantIdAndAccountId(String restaurantId, String accountId);
}
