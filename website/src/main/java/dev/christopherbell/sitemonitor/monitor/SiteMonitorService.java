package dev.christopherbell.sitemonitor.monitor;

import dev.christopherbell.account.api.MonitorAccountAccess;
import dev.christopherbell.libs.lease.LeaseGrant;
import dev.christopherbell.libs.lease.LeaseStore;
import dev.christopherbell.sitemonitor.api.MonitorProblem;
import dev.christopherbell.sitemonitor.fetch.MonitorFetchException;
import dev.christopherbell.sitemonitor.fetch.MonitorUrls;
import dev.christopherbell.sitemonitor.fetch.SiteMonitorDestinationPolicy;
import dev.christopherbell.sitemonitor.model.CreateMonitorSite;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Finding;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.FindingSeverity;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Page;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Report;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.ReportStatus;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Site;
import dev.christopherbell.sitemonitor.persistence.MonitorWorkspaceRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** Owns tenant isolation, bounded pilot capacity, explicit baselines and leased mutations. */
@Service
public class SiteMonitorService {
  private static final Duration LEASE_DURATION = Duration.ofMinutes(3);
  private static final String LEASE_NAME = "site-monitor-pilot";
  private static final int MAX_SITES_PER_WORKSPACE = 5;
  private static final int MAX_PILOT_WORKSPACES = 10;
  private static final int MAX_LABEL_LENGTH = 80;
  private static final int MAX_REPORTS_PER_SITE = 10;
  private static final Duration MANUAL_CHECK_INTERVAL = Duration.ofMinutes(15);
  private static final Duration SCHEDULED_CHECK_INTERVAL = Duration.ofDays(1);
  private static final Duration ORIGIN_RESOLUTION_TIMEOUT = Duration.ofSeconds(3);
  private static final String DEMO_LABEL = "Demonstration website";

  private final MonitorWorkspaceRepository workspaces;
  private final MonitorAccountAccess accounts;
  private final LeaseStore leases;
  private final SiteMonitorDestinationPolicy destinations;
  private final MonitorScanner scanner;
  private final Clock clock;

  public SiteMonitorService(MonitorWorkspaceRepository workspaces, MonitorAccountAccess accounts,
      LeaseStore leases, SiteMonitorDestinationPolicy destinations, MonitorScanner scanner,
      Clock clock) {
    this.workspaces = workspaces;
    this.accounts = accounts;
    this.leases = leases;
    this.destinations = destinations;
    this.scanner = scanner;
    this.clock = clock;
  }

  /** Returns the signed-in account's workspace, empty when it has no sites. */
  public MonitorWorkspace currentWorkspace() {
    String ownerAccountId = accounts.requireCurrentActiveAccount();
    return workspaces.findByAccountId(ownerAccountId)
        .orElse(MonitorWorkspace.emptyFor(ownerAccountId));
  }

  /**
   * Adds a site to the signed-in account's workspace, or the fixed demonstration site.
   *
   * @throws MonitorProblem 409 when the workspace or the pilot is full or the origin is already
   *     added; 400 for an origin that is not public HTTPS, invalid paths or an invalid label
   */
  public MonitorWorkspace addSite(CreateMonitorSite siteRequest) {
    String ownerAccountId = accounts.requireCurrentActiveAccount();
    return exclusively(lease -> {
      MonitorWorkspace workspace = workspaces.findByAccountId(ownerAccountId)
          .orElse(MonitorWorkspace.emptyFor(ownerAccountId));
      if (workspace.sites().size() >= MAX_SITES_PER_WORKSPACE) {
        throw new MonitorProblem(409, "The pilot allows five sites.");
      }
      boolean isNewWorkspace = workspace.version() == null;
      if (isNewWorkspace && workspaces.count() >= MAX_PILOT_WORKSPACES) {
        throw new MonitorProblem(409, "The pilot is currently full.");
      }
      Site newSite = validatedNewSite(siteRequest, workspace);
      List<Site> sites = new ArrayList<>(workspace.sites());
      sites.add(newSite);
      return save(lease, new MonitorWorkspace(
          workspace.id(), workspace.version(), ownerAccountId, workspace.generation(), sites));
    });
  }

  /**
   * Verifies ownership, captures the configured pages, and accepts them as the new baseline only
   * when every page is healthy; otherwise the previous baseline stays.
   */
  public MonitorWorkspace captureBaseline(String siteId) {
    String ownerAccountId = accounts.requireCurrentActiveAccount();
    return exclusively(lease -> runCheck(lease, ownerAccountId, siteId, CheckMode.CAPTURE_BASELINE));
  }

  /** Verifies ownership and compares the configured pages with the accepted baseline. */
  public MonitorWorkspace checkAgainstBaseline(String siteId) {
    String ownerAccountId = accounts.requireCurrentActiveAccount();
    return exclusively(lease -> runCheck(lease, ownerAccountId, siteId, CheckMode.COMPARE_WITH_BASELINE));
  }

  /** Removes a site with its baseline and reports; the last site removes the workspace. */
  public MonitorWorkspace removeSite(String siteId) {
    String ownerAccountId = accounts.requireCurrentActiveAccount();
    return exclusively(lease -> {
      MonitorWorkspace workspace = workspaces.findByAccountId(ownerAccountId)
          .orElseThrow(SiteMonitorService::siteOrReportNotFound);
      requireSite(workspace, siteId);
      List<Site> remainingSites = workspace.sites().stream()
          .filter(site -> !site.id().equals(siteId))
          .toList();
      if (remainingSites.isEmpty()) {
        requireLeaseHeld(lease);
        requireActiveAccount(ownerAccountId);
        workspaces.delete(workspace);
        return MonitorWorkspace.emptyFor(ownerAccountId);
      }
      return save(lease, new MonitorWorkspace(workspace.id(), workspace.version(), ownerAccountId,
          workspace.generation(), remainingSites));
    });
  }

  /** Renders one of the signed-in account's reports as client-ready text. */
  public String renderReport(String siteId, String reportId) {
    Site site = requireSite(currentWorkspace(), siteId);
    Report report = site.reports().stream()
        .filter(candidate -> candidate.id().equals(reportId))
        .findFirst()
        .orElseThrow(SiteMonitorService::siteOrReportNotFound);
    return MonitorComparison.renderReport(site.label(), site.origin(), report);
  }

  /**
   * Runs the scheduled comparison for at most one due site per minute across the pilot.
   *
   * <p>Workspaces of inactive accounts are deleted first. A site is due when it has a baseline
   * and no attempt in the last day; failed attempts also wait a day rather than hammer origins.
   * Another instance holding the lease is not an error.</p>
   */
  public void checkNextDueSite() {
    try {
      exclusively(lease -> {
        List<MonitorWorkspace> pilotWorkspaces = workspaces.listAll();
        deleteWorkspacesOfInactiveAccounts(lease, pilotWorkspaces);
        Optional<DueSite> nextDueSite = nextDueSite(pilotWorkspaces);
        if (nextDueSite.isPresent()) {
          requireLeaseHeld(lease);
          if (workspaces.claimScheduledMinute(clock.instant())) {
            DueSite dueSite = nextDueSite.get();
            runCheck(lease, dueSite.ownerAccountId(), dueSite.site().id(), CheckMode.COMPARE_WITH_BASELINE);
          }
        }
        return null;
      });
    } catch (MonitorProblem problem) {
      if (problem.status() != 409) {
        throw problem;
      }
    }
  }

  private Site validatedNewSite(CreateMonitorSite siteRequest, MonitorWorkspace workspace) {
    URI siteOrigin;
    List<String> pagePaths;
    try {
      siteOrigin = MonitorUrls.origin(siteRequest.demo() ? MonitorUrls.DEMO_ORIGIN : siteRequest.origin());
      pagePaths = siteRequest.demo()
          ? MonitorUrls.DEMO_PATHS
          : MonitorUrls.paths(siteOrigin, siteRequest.paths());
      destinations.resolveApproved(siteOrigin, ORIGIN_RESOLUTION_TIMEOUT);
    } catch (IllegalArgumentException | MonitorFetchException invalidSite) {
      throw new MonitorProblem(400, "Use a publicly reachable HTTPS origin and valid page paths.");
    }
    boolean originAlreadyAdded = workspace.sites().stream()
        .anyMatch(site -> site.origin().equals(siteOrigin.toString()));
    if (originAlreadyAdded) {
      throw new MonitorProblem(409, "This site is already in your workspace.");
    }
    String siteLabel = MonitorScanner.boundedText(siteRequest.demo() ? DEMO_LABEL : siteRequest.label());
    if (siteLabel.isBlank() || siteLabel.length() > MAX_LABEL_LENGTH) {
      throw new MonitorProblem(400, "Use a site label between one and 80 characters.");
    }
    String ownershipToken = UUID.randomUUID() + "-" + UUID.randomUUID();
    return new Site(UUID.randomUUID().toString(), siteLabel, siteOrigin.toString(), pagePaths,
        ownershipToken, siteRequest.demo(), null, null, null, List.of(), List.of());
  }

  /**
   * Runs one check while holding the lease. The attempt time is saved before any fetch, so the
   * cooldown applies even when the run fails part-way.
   */
  private MonitorWorkspace runCheck(
      LeaseGrant lease, String ownerAccountId, String siteId, CheckMode checkMode) {
    requireActiveAccount(ownerAccountId);
    MonitorWorkspace workspace = workspaces.findByAccountId(ownerAccountId)
        .orElseThrow(SiteMonitorService::siteOrReportNotFound);
    Site site = requireSite(workspace, siteId);
    long deadlineNanos = MonitorScanner.newRunDeadlineNanos();
    Runnable stopUnlessStillAuthorized = () -> {
      requireLeaseHeld(lease);
      requireActiveAccount(ownerAccountId);
    };
    boolean checkedRecently = site.lastAttempt() != null
        && site.lastAttempt().plus(MANUAL_CHECK_INTERVAL).isAfter(clock.instant());
    if (checkedRecently) {
      throw new MonitorProblem(429, "Checks are available once every 15 minutes per site.");
    }
    if (checkMode == CheckMode.COMPARE_WITH_BASELINE && !site.hasBaseline()) {
      throw new MonitorProblem(409, "Capture a healthy baseline before running comparisons.");
    }

    Site attemptedSite = withLastAttempt(site, clock.instant());
    MonitorWorkspace reservedWorkspace = save(lease, withSite(workspace, attemptedSite));
    String verificationProblem = "OWNERSHIP_NOT_VERIFIED";
    boolean ownershipVerified;
    try {
      ownershipVerified =
          scanner.isOwnershipVerified(attemptedSite, deadlineNanos, stopUnlessStillAuthorized);
    } catch (MonitorFetchException verificationFailure) {
      ownershipVerified = false;
      verificationProblem = verificationFailure.category();
    }
    if (!ownershipVerified) {
      Report unverifiedReport = new Report(UUID.randomUUID().toString(), clock.instant(),
          site.baselineOn(), ReportStatus.INCOMPLETE,
          List.of(new Finding(FindingSeverity.INCOMPLETE, "/", "ownership", "", verificationProblem)),
          List.of());
      return save(lease, withSite(reservedWorkspace,
          withReport(attemptedSite, unverifiedReport, false)));
    }

    List<Page> capturedPages =
        scanner.capturePages(attemptedSite, deadlineNanos, stopUnlessStillAuthorized);
    Report comparisonReport = MonitorComparison.compare(
        attemptedSite.baseline(), capturedPages, clock.instant(), site.baselineOn());
    boolean healthyCapture = capturedPages.size() == site.paths().size()
        && capturedPages.stream().allMatch(Page::healthy);
    if (checkMode == CheckMode.COMPARE_WITH_BASELINE) {
      return save(lease, withSite(reservedWorkspace,
          withReport(attemptedSite, comparisonReport, true)));
    }
    if (!healthyCapture) {
      Report rejectedBaselineReport = new Report(comparisonReport.id(), comparisonReport.checkedOn(),
          site.baselineOn(), ReportStatus.INCOMPLETE, comparisonReport.findings(), capturedPages);
      return save(lease, withSite(reservedWorkspace,
          withReport(attemptedSite, rejectedBaselineReport, true)));
    }
    Report acceptedBaselineReport = new Report(comparisonReport.id(), comparisonReport.checkedOn(),
        comparisonReport.checkedOn(), ReportStatus.BASELINE, comparisonReport.findings(), capturedPages);
    Site siteWithNewBaseline =
        withAcceptedBaseline(attemptedSite, capturedPages, acceptedBaselineReport.checkedOn());
    return save(lease, withSite(reservedWorkspace,
        withReport(siteWithNewBaseline, acceptedBaselineReport, true)));
  }

  private void deleteWorkspacesOfInactiveAccounts(LeaseGrant lease, List<MonitorWorkspace> pilotWorkspaces) {
    for (MonitorWorkspace workspace : pilotWorkspaces) {
      if (!accounts.isActive(workspace.accountId())) {
        requireLeaseHeld(lease);
        workspaces.delete(workspace);
      }
    }
  }

  private Optional<DueSite> nextDueSite(List<MonitorWorkspace> pilotWorkspaces) {
    Instant now = clock.instant();
    return pilotWorkspaces.stream()
        .filter(workspace -> accounts.isActive(workspace.accountId()))
        .flatMap(workspace -> workspace.sites().stream()
            .filter(site -> isDueForScheduledCheck(site, now))
            .map(site -> new DueSite(workspace.accountId(), site)))
        .min(Comparator.comparing(dueSite -> dueSite.site().lastAttempt(),
            Comparator.nullsFirst(Comparator.naturalOrder())));
  }

  private static boolean isDueForScheduledCheck(Site site, Instant now) {
    return site.hasBaseline()
        && (site.lastAttempt() == null || !site.lastAttempt().plus(SCHEDULED_CHECK_INTERVAL).isAfter(now));
  }

  private MonitorWorkspace save(LeaseGrant lease, MonitorWorkspace workspace) {
    requireLeaseHeld(lease);
    requireActiveAccount(workspace.accountId());
    return workspaces.save(workspace);
  }

  private void requireActiveAccount(String accountId) {
    if (!accounts.isActive(accountId)) {
      throw new MonitorProblem(403, "Active account required.");
    }
  }

  private void requireLeaseHeld(LeaseGrant lease) {
    if (leases.renew(lease, LEASE_DURATION).isEmpty()) {
      throw new MonitorProblem(409, "Another operation took ownership. Please retry.");
    }
  }

  /** Runs pilot work while holding the single pilot lease, releasing it on every exit. */
  private <T> T exclusively(Function<LeaseGrant, T> leasedWork) {
    LeaseGrant lease = leases.tryAcquire(LEASE_NAME, UUID.randomUUID().toString(), LEASE_DURATION)
        .orElseThrow(() -> new MonitorProblem(409, "A website check is running. Please retry shortly."));
    try {
      return leasedWork.apply(lease);
    } catch (OptimisticLockingFailureException concurrentChange) {
      throw new MonitorProblem(409, "Your workspace changed. Reload it and retry.");
    } finally {
      leases.release(lease);
    }
  }

  private static Site requireSite(MonitorWorkspace workspace, String siteId) {
    return workspace.sites().stream()
        .filter(site -> site.id().equals(siteId))
        .findFirst()
        .orElseThrow(SiteMonitorService::siteOrReportNotFound);
  }

  private static MonitorProblem siteOrReportNotFound() {
    return new MonitorProblem(404, "Site or report not found.");
  }

  private static MonitorWorkspace withSite(MonitorWorkspace workspace, Site updatedSite) {
    List<Site> sites = workspace.sites().stream()
        .map(site -> site.id().equals(updatedSite.id()) ? updatedSite : site)
        .toList();
    return new MonitorWorkspace(
        workspace.id(), workspace.version(), workspace.accountId(), workspace.generation(), sites);
  }

  private static Site withLastAttempt(Site site, Instant attemptedOn) {
    return new Site(site.id(), site.label(), site.origin(), site.paths(), site.token(), site.demo(),
        site.verifiedOn(), attemptedOn, site.baselineOn(), site.baseline(), site.reports());
  }

  /** Prepends a report, keeping the newest ten, and records whether ownership was verified. */
  private static Site withReport(Site site, Report report, boolean ownershipVerified) {
    List<Report> newestReports = new ArrayList<>();
    newestReports.add(report);
    newestReports.addAll(site.reports().stream().limit(MAX_REPORTS_PER_SITE - 1).toList());
    Instant verifiedOn = ownershipVerified ? report.checkedOn() : null;
    return new Site(site.id(), site.label(), site.origin(), site.paths(), site.token(), site.demo(),
        verifiedOn, site.lastAttempt(), site.baselineOn(), site.baseline(), newestReports);
  }

  private static Site withAcceptedBaseline(Site site, List<Page> baselinePages, Instant acceptedOn) {
    return new Site(site.id(), site.label(), site.origin(), site.paths(), site.token(), site.demo(),
        site.verifiedOn(), site.lastAttempt(), acceptedOn, baselinePages, site.reports());
  }

  private enum CheckMode {
    CAPTURE_BASELINE,
    COMPARE_WITH_BASELINE
  }

  private record DueSite(String ownerAccountId, Site site) {
  }
}
