package dev.christopherbell.federation.discovery;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import dev.christopherbell.federation.configuration.FederationProperties;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubActor;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.ActivityPubPublicKey;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.NodeInfoDiscovery;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.NodeInfoDocument;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.NodeInfoLink;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.NodeInfoServices;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.NodeInfoSoftware;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.NodeInfoUsage;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.NodeInfoUsers;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.WebFingerDocument;
import dev.christopherbell.federation.discovery.FederationDiscoveryModels.WebFingerLink;
import dev.christopherbell.federation.identity.FederationIdentity;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.post.PostRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Resolves bounded, read-only public federation discovery documents. */
@Service
public class FederationDiscoveryService {
  public static final String NODE_INFO_REL =
      "http://nodeinfo.diaspora.software/ns/schema/2.1";
  private static final String ACTIVITY_STREAMS_CONTEXT =
      "https://www.w3.org/ns/activitystreams";
  private static final String SECURITY_CONTEXT = "https://w3id.org/security/v1";
  private static final int MAX_RESOURCE_LENGTH = 320;
  private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,50}$");

  private final FederationProperties properties;
  private final String publicOrigin;
  private final String publicAuthority;
  private final AccountRepository accounts;
  private final PostRepository posts;

  public FederationDiscoveryService(
      FederationProperties properties,
      BrowserSecurityProperties browserSecurity,
      AccountRepository accounts,
      PostRepository posts
  ) {
    this.properties = Objects.requireNonNull(properties, "properties");
    var publicBaseUrl = Objects.requireNonNull(browserSecurity, "browserSecurity").publicBaseUrl();
    this.publicOrigin = publicBaseUrl.toString();
    this.publicAuthority = publicBaseUrl.getAuthority();
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.posts = Objects.requireNonNull(posts, "posts");
  }

  public WebFingerDocument webFinger(String resource)
      throws InvalidRequestException, ResourceNotFoundException {
    requireDiscovery();
    WebFingerAccount requested = parseWebFingerAccount(resource);
    if (!publicAuthority.equalsIgnoreCase(requested.authority())) {
      throw notFound();
    }
    Account account = findDiscoverableAccount(requested.username());
    FederationIdentity identity = requireValidIdentity(account);
    String subject = "acct:" + account.getUsername() + "@" + publicAuthority;
    return new WebFingerDocument(
        subject,
        List.of(identity.actorId()),
        List.of(new WebFingerLink("self", "application/activity+json", identity.actorId())));
  }

  public NodeInfoDiscovery nodeInfoDiscovery() throws ResourceNotFoundException {
    requireDiscovery();
    return new NodeInfoDiscovery(List.of(
        new NodeInfoLink(NODE_INFO_REL, publicOrigin + "/nodeinfo/2.1")));
  }

  public NodeInfoDocument nodeInfo() throws ResourceNotFoundException {
    requireDiscovery();
    return new NodeInfoDocument(
        "2.1",
        new NodeInfoSoftware(properties.softwareName(), properties.softwareVersion()),
        List.of("activitypub"),
        new NodeInfoServices(List.of(), List.of()),
        true,
        new NodeInfoUsage(
            new NodeInfoUsers(accounts.countByStatus(AccountStatus.ACTIVE), null, null),
            posts.count()),
        Map.of());
  }

  public ActivityPubActor actor(String username) throws ResourceNotFoundException {
    requireDiscovery();
    if (username == null || !USERNAME.matcher(username).matches()) {
      throw notFound();
    }
    Account account = findDiscoverableAccount(username);
    return actorForAccount(account);
  }

  Account actorAccount(String username) throws ResourceNotFoundException {
    requireDiscovery();
    if (username == null || !USERNAME.matcher(username).matches()) {
      throw notFound();
    }
    return findDiscoverableAccount(username);
  }

  ActivityPubActor actorForAccount(Account account) throws ResourceNotFoundException {
    FederationIdentity identity = requireValidIdentity(account);
    String actorId = identity.actorId();
    return new ActivityPubActor(
        List.of(ACTIVITY_STREAMS_CONTEXT, SECURITY_CONTEXT),
        actorId,
        "Person",
        account.getUsername(),
        account.getUsername(),
        actorId + "/inbox",
        actorId + "/outbox",
        actorId + "/followers",
        actorId + "/following",
        publicOrigin + "/u/" + account.getUsername(),
        new ActivityPubPublicKey(identity.keyId(), actorId, identity.publicKeyPem()));
  }

  private Account findDiscoverableAccount(String username) throws ResourceNotFoundException {
    return accounts.findByUsernameIgnoreCaseAndStatusAndFederationEnabledTrue(
            username, AccountStatus.ACTIVE)
        .orElseThrow(FederationDiscoveryService::notFound);
  }

  private FederationIdentity requireValidIdentity(Account account) throws ResourceNotFoundException {
    FederationIdentity identity = account.getFederationIdentity();
    String expectedActorId = publicOrigin + "/ap/users/" + account.getUsername();
    if (account.getStatus() != AccountStatus.ACTIVE
        || !account.isFederationEnabled()
        || identity == null
        || !expectedActorId.equals(identity.actorId())
        || !(expectedActorId + "#main-key").equals(identity.keyId())) {
      throw notFound();
    }
    return identity;
  }

  private WebFingerAccount parseWebFingerAccount(String resource)
      throws InvalidRequestException {
    if (resource == null
        || resource.isBlank()
        || resource.length() > MAX_RESOURCE_LENGTH
        || !resource.regionMatches(true, 0, "acct:", 0, 5)) {
      throw invalidResource();
    }
    String account = resource.substring(5);
    int separator = account.indexOf('@');
    if (separator < 1
        || separator != account.lastIndexOf('@')
        || separator == account.length() - 1) {
      throw invalidResource();
    }
    String username = account.substring(0, separator);
    String authority = account.substring(separator + 1);
    if (!USERNAME.matcher(username).matches()
        || authority.isBlank()
        || authority.chars().anyMatch(Character::isWhitespace)) {
      throw invalidResource();
    }
    return new WebFingerAccount(username, authority.toLowerCase(Locale.ROOT));
  }

  private void requireDiscovery() throws ResourceNotFoundException {
    if (!properties.discoveryEnabled()) {
      throw notFound();
    }
  }

  private static InvalidRequestException invalidResource() {
    return new InvalidRequestException("Invalid WebFinger resource.");
  }

  private static ResourceNotFoundException notFound() {
    return new ResourceNotFoundException("Federation resource not found.");
  }

  private record WebFingerAccount(String username, String authority) {}
}
