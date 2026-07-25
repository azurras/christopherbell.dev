package dev.christopherbell.configuration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** Serves public crawler metadata without inheriting immutable browser-asset caching. */
@Controller
public class PublicMetadataController {

  /** Returns the maintained crawler policy. */
  @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
  @ResponseBody
  public ResponseEntity<Resource> robots() {
    return metadata("static/robots.txt", MediaType.TEXT_PLAIN);
  }

  /** Returns the maintained canonical public-page sitemap. */
  @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
  @ResponseBody
  public ResponseEntity<Resource> sitemap() {
    return metadata("static/sitemap.xml", MediaType.APPLICATION_XML);
  }

  private ResponseEntity<Resource> metadata(String classpathLocation, MediaType contentType) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noCache())
        .contentType(contentType)
        .body(new ClassPathResource(classpathLocation));
  }
}
