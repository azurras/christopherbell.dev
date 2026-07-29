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
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
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
  private static final List<String> STATIC_PATHS = List.of(
      "/",
      "/blog",
      "/canes-box-tracker",
      "/photos",
      "/photos/usage",
      "/thebell",
      "/thebell/tony",
      "/vin-decoder",
      "/void",
      "/void/explore",
      "/wfl",
      "/wfl/top-rated",
      "/zip-coordinates");

  private final AccountRepository accounts;
  private final PostRepository posts;
  private final RestaurantRepository restaurants;
  private final Clock clock;
  private final int maxUrlsPerDocument;

  public PublicSitemapService(
      AccountRepository accounts,
      PostRepository posts,
      RestaurantRepository restaurants,
      Clock clock) {
    this(accounts, posts, restaurants, clock, MAX_URLS_PER_DOCUMENT);
  }

  PublicSitemapService(
      AccountRepository accounts,
      PostRepository posts,
      RestaurantRepository restaurants,
      Clock clock,
      int maxUrlsPerDocument) {
    if (maxUrlsPerDocument < 1 || maxUrlsPerDocument > MAX_URLS_PER_DOCUMENT) {
      throw new IllegalArgumentException("Sitemap document size is outside the supported range.");
    }
    this.accounts = accounts;
    this.posts = posts;
    this.restaurants = restaurants;
    this.clock = clock;
    this.maxUrlsPerDocument = maxUrlsPerDocument;
  }

  /** Renders either the sole URL set or an index pointing at bounded URL-set shards. */
  public String renderRoot() {
    var shards = snapshot();
    if (shards.size() == 1) {
      return renderUrlSet(shards.getFirst());
    }
    var locations = new ArrayList<String>(shards.size());
    for (int index = 1; index <= shards.size(); index++) {
      locations.add(PUBLIC_ROOT + "/sitemap-" + index + ".xml");
    }
    return renderElements("sitemapindex", "sitemap", locations);
  }

  /** Renders one one-based shard only when the root document is an index. */
  public Optional<String> renderShard(int pageNumber) {
    var shards = snapshot();
    if (shards.size() <= 1 || pageNumber < 1 || pageNumber > shards.size()) {
      return Optional.empty();
    }
    return Optional.of(renderUrlSet(shards.get(pageNumber - 1)));
  }

  private List<List<String>> snapshot() {
    SortedSet<String> locations = new TreeSet<>();
    STATIC_PATHS.forEach(path -> locations.add(PUBLIC_ROOT + path));
    collectPages(
        pageable -> accounts.findByStatus(AccountStatus.ACTIVE, pageable),
        account -> "/u/" + encode(account.getUsername()),
        locations);
    collectPages(
        pageable -> posts.findByExpiresOnAfter(clock.instant(), pageable),
        post -> "/p/" + encode(post.getId()),
        locations);
    collectPages(
        restaurants::findAll,
        restaurant -> "/wfl/restaurants/" + encode(restaurant.getId()),
        locations);

    var values = List.copyOf(locations);
    var shards = new ArrayList<List<String>>();
    for (int start = 0; start < values.size(); start += maxUrlsPerDocument) {
      shards.add(values.subList(start, Math.min(start + maxUrlsPerDocument, values.size())));
    }
    return List.copyOf(shards);
  }

  private <T> void collectPages(
      Function<org.springframework.data.domain.Pageable, Page<T>> query,
      Function<T, String> path,
      SortedSet<String> locations) {
    var pageNumber = 0;
    Page<T> page;
    do {
      var pageable = PageRequest.of(
          pageNumber, DATABASE_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id"));
      page = query.apply(pageable);
      page.getContent().stream()
          .map(path)
          .filter(value -> !value.endsWith("/null"))
          .map(value -> PUBLIC_ROOT + value)
          .forEach(locations::add);
      pageNumber++;
    } while (page.hasNext());
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

  private static String encode(String segment) {
    return segment == null ? "null" : UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8);
  }
}
