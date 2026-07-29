package dev.christopherbell.configuration;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantRepository;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

/** Produces bounded sitemap documents from public routes and live domain records. */
@Service
public class PublicSitemapService {
  static final int MAX_URLS_PER_DOCUMENT = 50_000;
  private static final int DATABASE_PAGE_SIZE = 1_000;
  private static final String PUBLIC_ROOT = "https://www.christopherbell.dev";
  private static final List<String> STATIC_URLS = List.of(
      PUBLIC_ROOT + "/",
      PUBLIC_ROOT + "/blog",
      PUBLIC_ROOT + "/canes-box-tracker",
      PUBLIC_ROOT + "/photos",
      PUBLIC_ROOT + "/photos/usage",
      PUBLIC_ROOT + "/thebell",
      PUBLIC_ROOT + "/thebell/tony",
      PUBLIC_ROOT + "/vin-decoder",
      PUBLIC_ROOT + "/void",
      PUBLIC_ROOT + "/void/explore",
      PUBLIC_ROOT + "/wfl",
      PUBLIC_ROOT + "/wfl/top-rated",
      PUBLIC_ROOT + "/zip-coordinates");

  private final AccountRepository accounts;
  private final PostRepository posts;
  private final RestaurantRepository restaurants;
  private final Clock clock;
  private final boolean expirationEnabled;
  private final int maxUrlsPerDocument;

  @Autowired
  public PublicSitemapService(
      AccountRepository accounts,
      PostRepository posts,
      RestaurantRepository restaurants,
      Clock clock,
      @Value("${posts.expiration.enabled:false}") boolean expirationEnabled) {
    this(
        accounts,
        posts,
        restaurants,
        clock,
        expirationEnabled,
        MAX_URLS_PER_DOCUMENT);
  }

  PublicSitemapService(
      AccountRepository accounts,
      PostRepository posts,
      RestaurantRepository restaurants,
      Clock clock,
      boolean expirationEnabled,
      int maxUrlsPerDocument) {
    if (maxUrlsPerDocument < 1 || maxUrlsPerDocument > MAX_URLS_PER_DOCUMENT) {
      throw new IllegalArgumentException("Sitemap document size is outside the supported range.");
    }
    this.accounts = accounts;
    this.posts = posts;
    this.restaurants = restaurants;
    this.clock = clock;
    this.expirationEnabled = expirationEnabled;
    this.maxUrlsPerDocument = maxUrlsPerDocument;
  }

  /** Renders either the sole URL set or an index pointing at bounded URL-set shards. */
  public String renderRoot() {
    var catalog = catalog();
    if (catalog.documentCount() == 1) {
      return renderUrlSet(loadRange(catalog, 0, catalog.totalUrls()));
    }
    var locations = new ArrayList<String>(catalog.documentCount());
    for (int page = 1; page <= catalog.documentCount(); page++) {
      locations.add(PUBLIC_ROOT + "/sitemap-" + page + ".xml");
    }
    return renderElements("sitemapindex", "sitemap", locations);
  }

  /** Renders one one-based shard without scanning records outside that bounded slice. */
  public Optional<String> renderShard(int pageNumber) {
    var catalog = catalog();
    if (catalog.documentCount() <= 1
        || pageNumber < 1
        || pageNumber > catalog.documentCount()) {
      return Optional.empty();
    }
    var start = Math.multiplyExact((long) pageNumber - 1, maxUrlsPerDocument);
    var length = Math.min(maxUrlsPerDocument, catalog.totalUrls() - start);
    return Optional.of(renderUrlSet(loadRange(catalog, start, length)));
  }

  private SitemapCatalog catalog() {
    var now = clock.instant();
    var accountCount = accounts.countByStatus(AccountStatus.ACTIVE);
    var postCount = expirationEnabled
        ? posts.countByExpiresOnAfter(now)
        : posts.count();
    var restaurantCount = restaurants.count();
    return new SitemapCatalog(
        now, accountCount, postCount, restaurantCount, maxUrlsPerDocument);
  }

  private List<String> loadRange(SitemapCatalog catalog, long start, long length) {
    var locations = new ArrayList<String>(Math.toIntExact(length));
    var end = Math.addExact(start, length);
    addStaticRange(locations, start, end);

    var accountStart = (long) STATIC_URLS.size();
    collectRange(
        locations,
        start,
        end,
        accountStart,
        catalog.accountCount(),
        pageable -> accounts.findByStatus(AccountStatus.ACTIVE, pageable),
        account -> PUBLIC_ROOT + "/u/" + encode(account.getUsername()));

    var postStart = Math.addExact(accountStart, catalog.accountCount());
    collectRange(
        locations,
        start,
        end,
        postStart,
        catalog.postCount(),
        pageable -> expirationEnabled
            ? posts.findByExpiresOnAfter(catalog.generatedAt(), pageable)
            : posts.findAll(pageable),
        post -> PUBLIC_ROOT + "/p/" + encode(post.getId()));

    var restaurantStart = Math.addExact(postStart, catalog.postCount());
    collectRange(
        locations,
        start,
        end,
        restaurantStart,
        catalog.restaurantCount(),
        restaurants::findAll,
        restaurant -> PUBLIC_ROOT + "/wfl/restaurants/" + encode(restaurant.getId()));
    return List.copyOf(locations);
  }

  private void addStaticRange(List<String> locations, long start, long end) {
    var overlapStart = Math.max(0, start);
    var overlapEnd = Math.min(STATIC_URLS.size(), end);
    if (overlapStart < overlapEnd) {
      locations.addAll(STATIC_URLS.subList(
          Math.toIntExact(overlapStart), Math.toIntExact(overlapEnd)));
    }
  }

  private <T> void collectRange(
      List<String> locations,
      long requestedStart,
      long requestedEnd,
      long sourceStart,
      long sourceCount,
      Function<org.springframework.data.domain.Pageable, Page<T>> query,
      Function<T, String> location) {
    var sourceEnd = Math.addExact(sourceStart, sourceCount);
    var overlapStart = Math.max(requestedStart, sourceStart);
    var overlapEnd = Math.min(requestedEnd, sourceEnd);
    if (overlapStart >= overlapEnd) {
      return;
    }

    var relativeStart = overlapStart - sourceStart;
    var remaining = overlapEnd - overlapStart;
    var pageNumber = Math.toIntExact(relativeStart / DATABASE_PAGE_SIZE);
    var offsetInPage = Math.toIntExact(relativeStart % DATABASE_PAGE_SIZE);
    while (remaining > 0) {
      var pageable = PageRequest.of(
          pageNumber, DATABASE_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id"));
      var content = query.apply(pageable).getContent();
      if (offsetInPage >= content.size()) {
        throw new IllegalStateException("Sitemap source changed while a document was generated.");
      }
      var take = Math.toIntExact(Math.min(remaining, content.size() - offsetInPage));
      content.subList(offsetInPage, offsetInPage + take).stream()
          .map(location)
          .forEach(locations::add);
      remaining -= take;
      pageNumber++;
      offsetInPage = 0;
      if (take == 0 && remaining > 0) {
        throw new IllegalStateException("Sitemap source returned an empty page unexpectedly.");
      }
    }
  }

  private String renderUrlSet(List<String> locations) {
    return renderElements("urlset", "url", locations);
  }

  private String renderElements(String rootElement, String entryElement, List<String> locations) {
    try {
      var output = new StringWriter();
      var writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
      writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
      writer.writeStartElement(rootElement);
      writer.writeDefaultNamespace("http://www.sitemaps.org/schemas/sitemap/0.9");
      for (var location : locations) {
        writer.writeStartElement(entryElement);
        writer.writeStartElement("loc");
        writer.writeCharacters(location);
        writer.writeEndElement();
        writer.writeEndElement();
      }
      writer.writeEndElement();
      writer.writeEndDocument();
      writer.close();
      return output.toString();
    } catch (XMLStreamException exception) {
      throw new IllegalStateException("Could not render sitemap XML.", exception);
    }
  }

  private static String encode(Object segment) {
    if (segment == null) {
      throw new IllegalStateException("Sitemap source has no public identifier.");
    }
    return UriUtils.encodePathSegment(segment.toString(), StandardCharsets.UTF_8);
  }

  private record SitemapCatalog(
      java.time.Instant generatedAt,
      long accountCount,
      long postCount,
      long restaurantCount,
      int documentSize
  ) {
    private long totalUrls() {
      return Math.addExact(
          STATIC_URLS.size(),
          Math.addExact(accountCount, Math.addExact(postCount, restaurantCount)));
    }

    private int documentCount() {
      return Math.toIntExact(Math.ceilDiv(totalUrls(), documentSize));
    }
  }
}
