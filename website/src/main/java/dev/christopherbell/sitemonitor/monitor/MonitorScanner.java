package dev.christopherbell.sitemonitor.monitor;

import dev.christopherbell.sitemonitor.fetch.MonitorFetchException;
import dev.christopherbell.sitemonitor.fetch.MonitorGateway;
import dev.christopherbell.sitemonitor.fetch.MonitorUrls;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Page;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Site;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/** Captures only configured HTML pages and at most ten same-origin assets in one 45s run. */
@Component
public class MonitorScanner {
  private static final Duration RUN_TIME_BUDGET = Duration.ofSeconds(45);
  private static final int MAX_ASSETS_PER_RUN = 10;
  private static final int MAX_ASSET_URL_LENGTH = 600;
  private static final int MAX_STORED_TEXT_LENGTH = 400;
  private static final String OWNERSHIP_PROOF_PATH = "/.well-known/christopherbell-site-monitor.txt";
  private static final String ASSET_SELECTOR = "script[src], link[rel=stylesheet][href], img[src]";
  private static final String ASSET_CHECK_INCOMPLETE = "ASSET_CHECK_INCOMPLETE";

  private final MonitorGateway gateway;

  public MonitorScanner(MonitorGateway gateway) {
    this.gateway = gateway;
  }

  /** Returns the monotonic deadline, in {@link System#nanoTime()} units, for a run starting now. */
  public static long newRunDeadlineNanos() {
    return System.nanoTime() + RUN_TIME_BUDGET.toNanos();
  }

  /** Bounds remote metadata and removes control characters before persistence and text export. */
  public static String boundedText(String remoteText) {
    String printableText = remoteText == null ? "" : remoteText.replaceAll("[\\p{Cntrl}]", " ").strip();
    return printableText.substring(0, Math.min(MAX_STORED_TEXT_LENGTH, printableText.length()));
  }

  public boolean isOwnershipVerified(Site site, long deadlineNanos) {
    return isOwnershipVerified(site, deadlineNanos, () -> {});
  }

  /**
   * Whether the site's owner published its exact token at the well-known proof path, without a
   * redirect. The demonstration site is verified by its fixed origin and paths instead.
   *
   * @param guard runs before any fetch and throws to stop the run, for example on lease loss
   */
  public boolean isOwnershipVerified(Site site, long deadlineNanos, Runnable guard) {
    guard.run();
    if (site.demo()) {
      return site.origin().equals(MonitorUrls.DEMO_ORIGIN) && site.paths().equals(MonitorUrls.DEMO_PATHS);
    }
    URI siteOrigin = URI.create(site.origin());
    URI proofUri = siteOrigin.resolve(OWNERSHIP_PROOF_PATH);
    MonitorGateway.Result proofResponse = gateway.fetch(proofUri, siteOrigin, deadlineNanos, false, guard);
    String publishedToken = new String(proofResponse.body(), StandardCharsets.UTF_8).strip();
    return proofResponse.status() == 200
        && proofResponse.finalUri().equals(proofUri)
        && publishedToken.equals(site.token());
  }

  public List<Page> capturePages(Site site, long deadlineNanos) {
    return capturePages(site, deadlineNanos, () -> {});
  }

  /**
   * Captures each configured page in order, sharing one budget of ten asset checks.
   *
   * @param guard runs before each fetch and throws to stop the run
   */
  public List<Page> capturePages(Site site, long deadlineNanos, Runnable guard) {
    URI siteOrigin = URI.create(site.origin());
    List<Page> capturedPages = new ArrayList<>();
    int remainingAssetChecks = MAX_ASSETS_PER_RUN;
    for (String pagePath : site.paths()) {
      Page capturedPage = capturePage(siteOrigin, pagePath, deadlineNanos, remainingAssetChecks, guard);
      capturedPages.add(capturedPage);
      remainingAssetChecks -= capturedPage.checkedAssets();
    }
    return List.copyOf(capturedPages);
  }

  private Page capturePage(URI siteOrigin, String pagePath, long deadlineNanos,
      int remainingAssetChecks, Runnable guard) {
    URI pageUri = siteOrigin.resolve(pagePath);
    try {
      MonitorGateway.Result pageResponse = gateway.fetch(pageUri, siteOrigin, deadlineNanos, false, guard);
      if (pageResponse.status() != 200) {
        return pageAfterConfirmingStatus(pageUri, siteOrigin, pagePath, pageResponse, deadlineNanos, guard);
      }
      if (!isHtml(pageResponse.contentType())) {
        throw new MonitorFetchException("PAGE_NOT_HTML");
      }
      Document pageDocument = Jsoup.parse(new ByteArrayInputStream(pageResponse.body()), null,
          pageResponse.finalUri().toString());
      AssetCheck assetCheck =
          checkSameOriginAssets(pageDocument, siteOrigin, deadlineNanos, remainingAssetChecks, guard);
      String robotsDirectives = "meta: " + pageDocument.select("meta[name=robots]").attr("content")
          + "; header: " + (pageResponse.robotsHeader() == null ? "" : pageResponse.robotsHeader());
      return new Page(pagePath, 200, boundedText(pageResponse.finalUri().toString()),
          boundedText(pageDocument.title()),
          boundedText(pageDocument.select("meta[name=description]").attr("content")),
          boundedText(pageDocument.select("link[rel=canonical]").attr("href")),
          boundedText(robotsDirectives), assetCheck.failedAssets(),
          assetCheck.checkedCount(), assetCheck.omittedCount(), assetCheck.problem(), false);
    } catch (MonitorFetchException fetchFailure) {
      return pageWithoutContent(pagePath, 0, pageUri.toString(), fetchFailure.category(), false);
    } catch (IOException parseFailure) {
      return pageWithoutContent(pagePath, 0, pageUri.toString(), "HTML_PARSE_FAILED", false);
    }
  }

  /** Refetches a non-200 page so one transient status is reported as incomplete, not failed. */
  private Page pageAfterConfirmingStatus(URI pageUri, URI siteOrigin, String pagePath,
      MonitorGateway.Result firstResponse, long deadlineNanos, Runnable guard) {
    MonitorGateway.Result confirmation = gateway.fetch(pageUri, siteOrigin, deadlineNanos, false, guard);
    boolean statusRepeated = firstResponse.status() == confirmation.status();
    String problem = statusRepeated ? "" : "HTTP_STATUS_CHANGED_DURING_CHECK";
    boolean repeatedFailure = statusRepeated && confirmation.status() != 200;
    return pageWithoutContent(
        pagePath, confirmation.status(), confirmation.finalUri().toString(), problem, repeatedFailure);
  }

  /**
   * HEAD-checks same-origin, query-free assets up to the remaining budget. A failing status is
   * confirmed by a second request; anything else that cannot be confirmed marks the page
   * incomplete.
   */
  private AssetCheck checkSameOriginAssets(Document pageDocument, URI siteOrigin, long deadlineNanos,
      int remainingAssetChecks, Runnable guard) {
    List<String> failedAssets = new ArrayList<>();
    Set<String> seenAssetUrls = new HashSet<>();
    int checkedCount = 0;
    int omittedCount = 0;
    String problem = "";
    for (Element assetElement : pageDocument.select(ASSET_SELECTOR)) {
      String urlAttribute = assetElement.hasAttr("src") ? "src" : "href";
      String assetUrl = assetElement.absUrl(urlAttribute);
      if (assetUrl.length() > MAX_ASSET_URL_LENGTH || !seenAssetUrls.add(assetUrl)) {
        continue;
      }
      URI assetUri;
      try {
        assetUri = URI.create(assetUrl);
      } catch (IllegalArgumentException invalidAssetUrl) {
        omittedCount++;
        continue;
      }
      if (checkedCount >= remainingAssetChecks || !MonitorUrls.sameOrigin(siteOrigin, assetUri)
          || assetUri.getRawQuery() != null) {
        omittedCount++;
        continue;
      }
      checkedCount++;
      try {
        int assetStatus = gateway.fetch(assetUri, siteOrigin, deadlineNanos, true, guard).status();
        if (assetStatus >= 400 && assetStatus != 405) {
          int confirmedStatus = gateway.fetch(assetUri, siteOrigin, deadlineNanos, true, guard).status();
          if (confirmedStatus == assetStatus) {
            failedAssets.add(boundedText(assetUri.getRawPath()) + " (" + assetStatus + ")");
          } else {
            problem = ASSET_CHECK_INCOMPLETE;
          }
        } else if (assetStatus < 200 || assetStatus >= 300) {
          problem = ASSET_CHECK_INCOMPLETE;
        }
      } catch (MonitorFetchException assetFetchFailure) {
        problem = ASSET_CHECK_INCOMPLETE;
      }
    }
    return new AssetCheck(failedAssets, checkedCount, omittedCount, problem);
  }

  private static boolean isHtml(String contentType) {
    if (contentType == null) {
      return false;
    }
    String normalizedContentType = contentType.toLowerCase(Locale.ROOT);
    return normalizedContentType.startsWith("text/html")
        || normalizedContentType.startsWith("application/xhtml+xml");
  }

  private static Page pageWithoutContent(String pagePath, int status, String finalUrl,
      String problem, boolean repeatedFailure) {
    return new Page(pagePath, status, boundedText(finalUrl), "", "", "", "", List.of(), 0, 0,
        problem, repeatedFailure);
  }

  private record AssetCheck(
      List<String> failedAssets, int checkedCount, int omittedCount, String problem) {
  }
}
