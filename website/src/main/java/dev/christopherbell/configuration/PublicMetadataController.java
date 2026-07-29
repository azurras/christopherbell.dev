package dev.christopherbell.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

/** Serves public crawler metadata without inheriting immutable browser-asset caching. */
@Controller
@RequiredArgsConstructor
public class PublicMetadataController {
  private final PublicSitemapService sitemaps;

  /** Returns the maintained crawler policy. */
  @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
  @ResponseBody
  public ResponseEntity<Resource> robots() {
    return metadata("static/robots.txt", MediaType.TEXT_PLAIN);
  }

  /** Returns the maintained canonical public-page sitemap. */
  @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
  @ResponseBody
  public ResponseEntity<String> sitemap() {
    return xml(sitemaps.renderRoot());
  }

  /** Returns one bounded sitemap shard when the root document is an index. */
  @GetMapping(value = "/sitemap-{page}.xml", produces = MediaType.APPLICATION_XML_VALUE)
  @ResponseBody
  public ResponseEntity<String> sitemapShard(@PathVariable int page) {
    return sitemaps.renderShard(page)
        .map(this::xml)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private ResponseEntity<Resource> metadata(String classpathLocation, MediaType contentType) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noCache())
        .contentType(contentType)
        .body(new ClassPathResource(classpathLocation));
  }

  private ResponseEntity<String> xml(String body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noCache())
        .contentType(MediaType.APPLICATION_XML)
        .body(body);
  }
}
