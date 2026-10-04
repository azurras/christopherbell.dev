package dev.christopherbell.sitemonitor.monitor;

import dev.christopherbell.sitemonitor.fetch.MonitorFetchException;
import dev.christopherbell.sitemonitor.fetch.MonitorGateway;
import dev.christopherbell.sitemonitor.fetch.MonitorUrls;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Page;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Site;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

/** Captures only configured HTML pages and at most ten same-origin assets in one 45s run. */
@Component
public class MonitorScanner {
  private final MonitorGateway gateway;
  public MonitorScanner(MonitorGateway gateway) { this.gateway = gateway; }

  public boolean verify(Site site, long deadline) {
    return verify(site, deadline, () -> {});
  }
  public boolean verify(Site site, long deadline, Runnable guard) {
    guard.run();
    if (site.demo()) return site.origin().equals(MonitorUrls.DEMO_ORIGIN)
        && site.paths().equals(MonitorUrls.DEMO_PATHS);
    URI origin = URI.create(site.origin());
    URI proof = origin.resolve("/.well-known/christopherbell-site-monitor.txt");
    var response = gateway.fetch(proof, origin, deadline, false, guard);
    return response.status() == 200 && response.finalUri().equals(proof)
        && new String(response.body(), StandardCharsets.UTF_8).strip().equals(site.token());
  }

  public List<Page> capture(Site site, long deadline) {
    return capture(site, deadline, () -> {});
  }
  public List<Page> capture(Site site, long deadline, Runnable guard) {
    URI origin = URI.create(site.origin());
    List<Page> pages = new ArrayList<>();
    int availableAssets = 10;
    for (String path : site.paths()) {
      Page page = capturePage(origin, path, deadline, availableAssets, guard);
      pages.add(page);
      availableAssets -= page.checkedAssets();
    }
    return List.copyOf(pages);
  }

  private Page capturePage(URI origin, String path, long deadline, int availableAssets, Runnable guard) {
    URI pageUri = origin.resolve(path);
    try {
      var response = gateway.fetch(pageUri, origin, deadline, false, guard);
      if (response.status() != 200) {
        var confirmation = gateway.fetch(pageUri, origin, deadline, false, guard);
        return emptyPage(path, confirmation.status(), confirmation.finalUri().toString(),
            response.status() == confirmation.status() ? "" : "HTTP_STATUS_CHANGED_DURING_CHECK",
            response.status() == confirmation.status() && confirmation.status() != 200);
      }
      if (response.contentType() == null || !(response.contentType().toLowerCase(Locale.ROOT)
          .startsWith("text/html") || response.contentType().toLowerCase(Locale.ROOT)
          .startsWith("application/xhtml+xml"))) {
        throw new MonitorFetchException("PAGE_NOT_HTML");
      }
      var document = Jsoup.parse(new java.io.ByteArrayInputStream(response.body()), null,
          response.finalUri().toString());
      List<String> failedAssets = new ArrayList<>();
      int checkedAssets = 0;
      int omittedAssets = 0;
      String problem = "";
      var assets = document.select("script[src], link[rel=stylesheet][href], img[src]");
      var seenAssets = new java.util.HashSet<String>();
      for (var element : assets) {
        String attribute = element.hasAttr("src") ? "src" : "href";
        String assetUrl = element.absUrl(attribute);
        if (assetUrl.length() > 600 || !seenAssets.add(assetUrl)) continue;
        URI asset;
        try { asset = URI.create(assetUrl); }
        catch (IllegalArgumentException invalid) { omittedAssets++; continue; }
        if (checkedAssets >= availableAssets || !MonitorUrls.sameOrigin(origin, asset)
            || asset.getRawQuery() != null) { omittedAssets++; continue; }
        checkedAssets++;
        try {
          int status = gateway.fetch(asset, origin, deadline, true, guard).status();
          if (status >= 400 && status != 405) {
            int confirmation = gateway.fetch(asset, origin, deadline, true, guard).status();
            if (confirmation == status) failedAssets.add(text(asset.getRawPath()) + " (" + status + ")");
            else problem = "ASSET_CHECK_INCOMPLETE";
          } else if (status < 200 || status >= 300) problem = "ASSET_CHECK_INCOMPLETE";
        } catch (MonitorFetchException failure) { problem = "ASSET_CHECK_INCOMPLETE"; }
      }
      return new Page(path, 200, text(response.finalUri().toString()), text(document.title()),
          text(document.select("meta[name=description]").attr("content")),
          text(document.select("link[rel=canonical]").attr("href")),
          text("meta: " + document.select("meta[name=robots]").attr("content")
              + "; header: " + (response.robotsHeader() == null ? "" : response.robotsHeader())), failedAssets,
          checkedAssets, omittedAssets, problem, false);
    } catch (MonitorFetchException failure) {
      return emptyPage(path, 0, pageUri.toString(), failure.category(), false);
    } catch (java.io.IOException failure) {
      return emptyPage(path, 0, pageUri.toString(), "HTML_PARSE_FAILED", false);
    }
  }

  private static Page emptyPage(String path, int status, String finalUrl, String problem,
      boolean repeatedFailure) {
    return new Page(path, status, text(finalUrl), "", "", "", "", List.of(), 0, 0,
        problem, repeatedFailure);
  }

  /** Bound remote metadata and remove control characters before persistence and text export. */
  public static String text(String value) {
    String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").strip();
    return clean.substring(0, Math.min(400, clean.length()));
  }

  public static long newDeadline() {
    return System.nanoTime() + Duration.ofSeconds(45).toNanos();
  }
}
