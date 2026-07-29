package dev.christopherbell.federation.discovery;

import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubActor;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.NodeInfoDiscovery;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.NodeInfoDocument;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.WebFingerDocument;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubCreate;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubOrderedCollection;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exact anonymous GET routes for local ActivityPub and NodeInfo discovery. */
@RestController
public class FederationDiscoveryController {
  private static final MediaType JRD_JSON = MediaType.parseMediaType("application/jrd+json");
  private static final MediaType ACTIVITY_JSON =
      MediaType.parseMediaType("application/activity+json");
  private static final MediaType NODE_INFO_JSON = MediaType.parseMediaType(
      "application/json; profile=\"http://nodeinfo.diaspora.software/ns/schema/2.1#\"");

  private final FederationDiscoveryService discovery;
  private final FederationCollectionService collections;

  public FederationDiscoveryController(
      FederationDiscoveryService discovery,
      FederationCollectionService collections
  ) {
    this.discovery = discovery;
    this.collections = collections;
  }

  @GetMapping(value = "/.well-known/webfinger", produces = "application/jrd+json")
  public ResponseEntity<WebFingerDocument> webFinger(@RequestParam String resource)
      throws InvalidRequestException, ResourceNotFoundException {
    return publicNoStore(discovery.webFinger(resource), JRD_JSON);
  }

  @GetMapping(value = "/.well-known/nodeinfo", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<NodeInfoDiscovery> nodeInfoDiscovery()
      throws ResourceNotFoundException {
    return publicNoStore(discovery.nodeInfoDiscovery(), MediaType.APPLICATION_JSON);
  }

  @GetMapping(value = "/nodeinfo/2.1", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<NodeInfoDocument> nodeInfo() throws ResourceNotFoundException {
    return publicNoStore(discovery.nodeInfo(), NODE_INFO_JSON);
  }

  @GetMapping(value = "/ap/users/{username}", produces = "application/activity+json")
  public ResponseEntity<ActivityPubActor> actor(@PathVariable String username)
      throws ResourceNotFoundException {
    return publicNoStore(discovery.actor(username), ACTIVITY_JSON);
  }

  @GetMapping(value = "/ap/users/{username}/outbox", produces = "application/activity+json")
  public ResponseEntity<ActivityPubOrderedCollection<ActivityPubCreate>> outbox(
      @PathVariable String username,
      @RequestParam(defaultValue = "false") boolean page,
      @RequestParam(defaultValue = "") String cursor,
      @RequestParam(defaultValue = "20") int size
  ) throws ResourceNotFoundException, InvalidRequestException {
    return publicNoStore(collections.outbox(username, page, cursor, size), ACTIVITY_JSON);
  }

  @GetMapping(value = "/ap/users/{username}/followers", produces = "application/activity+json")
  public ResponseEntity<ActivityPubOrderedCollection<String>> followers(
      @PathVariable String username
  ) throws ResourceNotFoundException {
    return publicNoStore(collections.followers(username), ACTIVITY_JSON);
  }

  @GetMapping(value = "/ap/users/{username}/following", produces = "application/activity+json")
  public ResponseEntity<ActivityPubOrderedCollection<String>> following(
      @PathVariable String username
  ) throws ResourceNotFoundException {
    return publicNoStore(collections.following(username), ACTIVITY_JSON);
  }

  private static <T> ResponseEntity<T> publicNoStore(T body, MediaType contentType) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        .contentType(contentType)
        .body(body);
  }
}
