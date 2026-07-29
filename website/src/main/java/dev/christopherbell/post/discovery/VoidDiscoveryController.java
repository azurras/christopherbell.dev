package dev.christopherbell.post.discovery;

import static dev.christopherbell.libs.api.APIVersion.V20260728;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.post.model.PostFeedItem;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous, read-only discovery endpoints for active Void conversations. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/posts" + V20260728 + "/discovery")
public class VoidDiscoveryController {
  private final VoidDiscoveryService discovery;
  private final VoidPeopleDiscoveryService peopleDiscovery;

  @GetMapping(value = "/new", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<VoidDiscoveryPage<PostFeedItem>>> newArrivals(
      @RequestParam(defaultValue = "") String cursor,
      @RequestParam(defaultValue = "12") int size
  ) throws InvalidRequestException {
    return noStore(discovery.newArrivals(cursor, size));
  }

  @GetMapping(value = "/fading", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<VoidDiscoveryPage<PostFeedItem>>> fadingSoon(
      @RequestParam(defaultValue = "") String cursor,
      @RequestParam(defaultValue = "12") int size
  ) throws InvalidRequestException {
    return noStore(discovery.fadingSoon(cursor, size));
  }

  @GetMapping(value = "/revived", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<VoidDiscoveryPage<PostFeedItem>>> recentlyRevived(
      @RequestParam(defaultValue = "") String cursor,
      @RequestParam(defaultValue = "12") int size
  ) throws InvalidRequestException {
    return noStore(discovery.recentlyRevived(cursor, size));
  }

  @GetMapping(value = "/topics", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<VoidDiscoveryPage<VoidTopicSummary>>> topics(
      @RequestParam(defaultValue = "") String cursor,
      @RequestParam(defaultValue = "12") int size
  ) throws InvalidRequestException {
    return noStore(discovery.topics(cursor, size));
  }

  @GetMapping(value = "/people", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<List<VoidPersonSuggestion>>> people() {
    return noStore(peopleDiscovery.suggestions());
  }

  @GetMapping(value = "/topic/{topic}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<VoidDiscoveryPage<PostFeedItem>>> topic(
      @PathVariable String topic,
      @RequestParam(defaultValue = "") String cursor,
      @RequestParam(defaultValue = "12") int size
  ) throws InvalidRequestException {
    return noStore(discovery.topic(topic, cursor, size));
  }

  private static <T> ResponseEntity<Response<T>> noStore(T payload) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(Response.<T>builder().payload(payload).success(true).build());
  }
}
