package dev.christopherbell.federation.discovery;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubActor;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubPublicKey;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubOrderedCollection;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.NodeInfoDiscovery;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.NodeInfoLink;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.WebFingerDocument;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.WebFingerLink;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class FederationDiscoveryControllerTest {
  @Mock private FederationDiscoveryService discovery;
  @Mock private FederationCollectionService collections;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders
        .standaloneSetup(new FederationDiscoveryController(discovery, collections))
        .setControllerAdvice(new ControllerExceptionHandler())
        .build();
  }

  @Test
  void webFingerReturnsJrdCorsAndNoStoreHeaders() throws Exception {
    String resource = "acct:chris@www.christopherbell.dev";
    when(discovery.webFinger(resource)).thenReturn(new WebFingerDocument(
        resource,
        List.of("https://www.christopherbell.dev/ap/users/chris"),
        List.of(new WebFingerLink(
            "self",
            "application/activity+json",
            "https://www.christopherbell.dev/ap/users/chris"))));

    mockMvc.perform(get("/.well-known/webfinger").param("resource", resource))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/jrd+json"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"))
        .andExpect(jsonPath("$.subject").value(resource))
        .andExpect(jsonPath("$.links[0].rel").value("self"));
  }

  @Test
  void nodeInfoDiscoveryReturnsTheSchemaLinkWithoutCaching() throws Exception {
    when(discovery.nodeInfoDiscovery()).thenReturn(new NodeInfoDiscovery(List.of(
        new NodeInfoLink(
            FederationDiscoveryService.NODE_INFO_REL,
            "https://www.christopherbell.dev/nodeinfo/2.1"))));

    mockMvc.perform(get("/.well-known/nodeinfo"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/json"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
        .andExpect(jsonPath("$.links[0].href")
            .value("https://www.christopherbell.dev/nodeinfo/2.1"));
  }

  @Test
  void actorReturnsActivityJsonWithoutLeakingPrivateIdentityFields() throws Exception {
    String actorId = "https://www.christopherbell.dev/ap/users/chris";
    when(discovery.actor("chris")).thenReturn(new ActivityPubActor(
        List.of("https://www.w3.org/ns/activitystreams"),
        actorId,
        "Person",
        "chris",
        "chris",
        actorId + "/inbox",
        actorId + "/outbox",
        actorId + "/followers",
        actorId + "/following",
        "https://www.christopherbell.dev/u/chris",
        new ActivityPubPublicKey(actorId + "#main-key", actorId, "public-pem")));

    mockMvc.perform(get("/ap/users/chris"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/activity+json"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
        .andExpect(jsonPath("$.@context[0]")
            .value("https://www.w3.org/ns/activitystreams"))
        .andExpect(jsonPath("$.id").value(actorId))
        .andExpect(jsonPath("$.publicKey.id").value(actorId + "#main-key"))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("ciphertext"))));
  }

  @Test
  void disabledDiscoveryReturnsNotFoundWithoutAccountDetails() throws Exception {
    when(discovery.actor("chris"))
        .thenThrow(new ResourceNotFoundException("Federation resource not found."));

    mockMvc.perform(get("/ap/users/chris"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("account-"))));
  }

  @Test
  void outboxAndRelationshipReadsReturnActivityJsonWithoutCaching() throws Exception {
    String outboxId = "https://www.christopherbell.dev/ap/users/chris/outbox";
    when(collections.outbox("chris", true, "", 20)).thenReturn(
        new ActivityPubOrderedCollection<>(
            List.of("https://www.w3.org/ns/activitystreams"),
            outboxId + "?page=true",
            "OrderedCollectionPage",
            0,
            null,
            outboxId,
            List.of(),
            null));

    mockMvc.perform(get("/ap/users/chris/outbox").param("page", "true"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/activity+json"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
        .andExpect(jsonPath("$.type").value("OrderedCollectionPage"))
        .andExpect(jsonPath("$.partOf").value(outboxId));
  }

}
