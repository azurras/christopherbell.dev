package dev.christopherbell.blog;

import dev.christopherbell.blog.model.BlogResponse;
import dev.christopherbell.blog.model.Post;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Blog posts with fixed values for blog tests. */
final class BlogStub {

  static final UUID MIATA_POST_ID = UUID.fromString("5493c406-9a16-45a6-9592-d489bc36cb7a");
  static final UUID ROAD_TRIP_POST_ID = UUID.fromString("7b1f3c2e-8d4a-4e6b-9c0d-2a5e6f7b8c9d");
  static final UUID ABSENT_POST_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
  static final Instant PUBLISHED_ON = Instant.parse("2021-08-31T00:00:00Z");

  private BlogStub() {
  }

  static Post miataPost() {
    return new Post(
        "CBell",
        "It is small and red.",
        PUBLISHED_ON,
        "The little red miata.",
        MIATA_POST_ID,
        null,
        List.of("cars"),
        "Little Red Miata");
  }

  static Post roadTripPost() {
    return new Post(
        "CBell", "Miles and miles.", null, "A road trip.", ROAD_TRIP_POST_ID, null, null,
        "Road Trip");
  }

  static List<Post> configuredPosts() {
    return List.of(miataPost(), roadTripPost());
  }

  static BlogResponse allPostsResponse() {
    return new BlogResponse(configuredPosts());
  }
}
