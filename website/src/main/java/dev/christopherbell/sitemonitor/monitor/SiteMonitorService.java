package dev.christopherbell.sitemonitor.monitor;

import dev.christopherbell.account.api.MonitorAccountAccess;
import dev.christopherbell.libs.lease.LeaseGrant;
import dev.christopherbell.libs.lease.LeaseStore;
import dev.christopherbell.sitemonitor.api.MonitorProblem;
import dev.christopherbell.sitemonitor.fetch.MonitorFetchException;
import dev.christopherbell.sitemonitor.fetch.MonitorUrls;
import dev.christopherbell.sitemonitor.fetch.SiteMonitorDestinationPolicy;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Report;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace.Site;
import dev.christopherbell.sitemonitor.persistence.MonitorWorkspaceRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Owns tenant isolation, bounded pilot capacity, explicit baselines and leased mutations. */
@Service
public class SiteMonitorService {
  private static final Duration LEASE_DURATION = Duration.ofMinutes(3);
  private static final String LEASE_NAME = "site-monitor-pilot";
  private final MonitorWorkspaceRepository workspaces;
  private final MonitorAccountAccess accounts;
  private final LeaseStore leases;
  private final SiteMonitorDestinationPolicy destinations;
  private final MonitorScanner scanner;
  private final Clock clock;

  public SiteMonitorService(MonitorWorkspaceRepository workspaces, MonitorAccountAccess accounts,
      LeaseStore leases, SiteMonitorDestinationPolicy destinations, MonitorScanner scanner,
      Clock clock) {
    this.workspaces = workspaces; this.accounts = accounts; this.leases = leases;
    this.destinations = destinations; this.scanner = scanner; this.clock = clock;
  }

  public MonitorWorkspace currentWorkspace() {
    String owner = accounts.requireCurrentActiveAccount();
    return workspaces.find(owner).orElse(new MonitorWorkspace(null, null, owner, List.of()));
  }

  public MonitorWorkspace addSite(String label, String origin, List<String> paths, boolean demo) {
    String owner = accounts.requireCurrentActiveAccount();
    return exclusively(grant -> {
      var workspace = workspaces.find(owner).orElse(new MonitorWorkspace(null, null, owner, List.of()));
      if (workspace.sites().size() >= 5) throw new MonitorProblem(409, "The pilot allows five sites.");
      if (workspace.version() == null && workspaces.count() >= 10) {
        throw new MonitorProblem(409, "The pilot is currently full.");
      }
      final URI siteOrigin;
      final List<String> pagePaths;
      try {
        siteOrigin = MonitorUrls.origin(demo ? MonitorUrls.DEMO_ORIGIN : origin);
        pagePaths = demo ? MonitorUrls.DEMO_PATHS : MonitorUrls.paths(siteOrigin, paths);
        destinations.resolveApproved(siteOrigin, Duration.ofSeconds(3));
      } catch (IllegalArgumentException | MonitorFetchException invalid) {
        throw new MonitorProblem(400, "Use a publicly reachable HTTPS origin and valid page paths.");
      }
      if (workspace.sites().stream().anyMatch(site -> site.origin().equals(siteOrigin.toString()))) {
        throw new MonitorProblem(409, "This site is already in your workspace.");
      }
      String siteLabel = MonitorScanner.text(demo ? "Demonstration website" : label);
      if (siteLabel.isBlank() || siteLabel.length() > 80) {
        throw new MonitorProblem(400, "Use a site label between one and 80 characters.");
      }
      var site = new Site(UUID.randomUUID().toString(), siteLabel, siteOrigin.toString(), pagePaths,
          UUID.randomUUID() + "-" + UUID.randomUUID(), demo, null, null, null, List.of(), List.of());
      List<Site> sites = new ArrayList<>(workspace.sites()); sites.add(site);
      return save(grant, new MonitorWorkspace(workspace.id(), workspace.version(), owner, workspace.generation(), sites));
    });
  }

  public MonitorWorkspace run(String siteId, boolean acceptBaseline) {
    String owner = accounts.requireCurrentActiveAccount();
    return exclusively(grant -> runForOwner(grant, owner, siteId, acceptBaseline));
  }

  private MonitorWorkspace runForOwner(LeaseGrant grant, String owner, String siteId,
      boolean acceptBaseline) {
    if (!accounts.isActive(owner)) throw new MonitorProblem(403, "Active account required.");
    var workspace = workspaces.find(owner).orElseThrow(() -> missing());
    Site site = requireSite(workspace, siteId);
    long deadline = MonitorScanner.newDeadline();
    Runnable guard = () -> { requireHeld(grant); requireActive(owner); };
    if (site.lastAttempt() != null
        && site.lastAttempt().plus(Duration.ofMinutes(15)).isAfter(clock.instant())) {
      throw new MonitorProblem(429, "Checks are available once every 15 minutes per site.");
    }
    if (!acceptBaseline && site.baseline().isEmpty()) {
      throw new MonitorProblem(409, "Capture a healthy baseline before running comparisons.");
    }
    Site attempted = new Site(site.id(), site.label(), site.origin(), site.paths(), site.token(),
        site.demo(), site.verifiedOn(), clock.instant(), site.baselineOn(), site.baseline(), site.reports());
    var reservedWorkspace = save(grant, replace(workspace, attempted));
    boolean verified;
    String verificationProblem = "OWNERSHIP_NOT_VERIFIED";
    try { verified = scanner.verify(attempted, deadline, guard); }
    catch (MonitorFetchException failure) { verified = false; verificationProblem = failure.category(); }
    if (!verified) {
      var report = new Report(UUID.randomUUID().toString(), clock.instant(), site.baselineOn(), "INCOMPLETE",
          List.of(new MonitorWorkspace.Finding("INCOMPLETE", "/", "ownership", "", verificationProblem)),
          List.of());
      return save(grant, replace(reservedWorkspace, withReport(attempted, report, null, false)));
    }
    var pages = scanner.capture(attempted, deadline, guard);
    var report = MonitorComparison.compare(attempted.baseline(), pages, clock.instant(), site.baselineOn());
    boolean healthyCapture = pages.size() == site.paths().size() && pages.stream().allMatch(p -> p.healthy());
    if (acceptBaseline && !healthyCapture) {
      report = new Report(report.id(), report.checkedOn(), site.baselineOn(), "INCOMPLETE", report.findings(), pages);
    } else if (acceptBaseline) {
      report = new Report(report.id(), report.checkedOn(), report.checkedOn(), "BASELINE", report.findings(), pages);
    }
    return save(grant, replace(reservedWorkspace, withReport(attempted, report,
        acceptBaseline && healthyCapture ? pages : null, true)));
  }

  public MonitorWorkspace removeSite(String siteId) {
    String owner = accounts.requireCurrentActiveAccount();
    return exclusively(grant -> {
      var workspace = workspaces.find(owner).orElseThrow(() -> missing());
      requireSite(workspace, siteId);
      var remaining = workspace.sites().stream().filter(site -> !site.id().equals(siteId)).toList();
      if (remaining.isEmpty()) {
        requireHeld(grant); requireActive(owner); workspaces.delete(workspace);
        return new MonitorWorkspace(null, null, owner, List.of());
      }
      return save(grant, new MonitorWorkspace(workspace.id(), workspace.version(), owner, workspace.generation(), remaining));
    });
  }

  public String report(String siteId, String reportId) {
    Site site = requireSite(currentWorkspace(), siteId);
    Report report = site.reports().stream().filter(value -> value.id().equals(reportId))
        .findFirst().orElseThrow(() -> missing());
    return MonitorComparison.renderReport(site.label(), site.origin(), report);
  }

  /** At most one due site per minute; failed attempts also wait a day rather than hammer origins. */
  public void checkNextDueSite() {
    try {
      exclusively(grant -> {
        var available = workspaces.list();
        for (var workspace : available) {
          if (!accounts.isActive(workspace.accountId())) {
            requireHeld(grant); workspaces.delete(workspace);
          }
        }
        var due = available.stream().filter(workspace -> accounts.isActive(workspace.accountId()))
            .flatMap(workspace -> workspace.sites().stream()
                .filter(site -> !site.baseline().isEmpty() && (site.lastAttempt() == null
                    || !site.lastAttempt().plus(Duration.ofDays(1)).isAfter(clock.instant())))
                .map(site -> new Due(workspace.accountId(), site)))
            .min(Comparator.comparing(value -> value.site().lastAttempt(),
                Comparator.nullsFirst(Comparator.naturalOrder())));
        due.ifPresent(value -> {
          requireHeld(grant);
          if (workspaces.claimScheduledMinute(clock.instant())) {
            runForOwner(grant, value.owner(), value.site().id(), false);
          }
        });
        return null;
      });
    } catch (MonitorProblem failure) {
      if (failure.status() != 409) throw failure;
    }
  }

  private MonitorWorkspace save(LeaseGrant grant, MonitorWorkspace workspace) {
    requireHeld(grant);
    requireActive(workspace.accountId());
    return workspaces.save(workspace);
  }
  private void requireActive(String owner) {
    if (!accounts.isActive(owner)) throw new MonitorProblem(403, "Active account required.");
  }
  private void requireHeld(LeaseGrant grant) {
    if (leases.renew(grant, LEASE_DURATION).isEmpty()) {
      throw new MonitorProblem(409, "Another operation took ownership. Please retry.");
    }
  }
  private <T> T exclusively(Function<LeaseGrant, T> work) {
    var grant = leases.tryAcquire(LEASE_NAME, UUID.randomUUID().toString(), LEASE_DURATION)
        .orElseThrow(() -> new MonitorProblem(409, "A website check is running. Please retry shortly."));
    try { return work.apply(grant); }
    catch (org.springframework.dao.OptimisticLockingFailureException changed) {
      throw new MonitorProblem(409, "Your workspace changed. Reload it and retry.");
    }
    finally { leases.release(grant); }
  }
  private static Site requireSite(MonitorWorkspace workspace, String siteId) {
    return workspace.sites().stream().filter(site -> site.id().equals(siteId)).findFirst()
        .orElseThrow(() -> missing());
  }
  private static MonitorProblem missing() { return new MonitorProblem(404, "Site or report not found."); }
  private static MonitorWorkspace replace(MonitorWorkspace workspace, Site updated) {
    return new MonitorWorkspace(workspace.id(), workspace.version(), workspace.accountId(), workspace.generation(), workspace.sites().stream()
        .map(site -> site.id().equals(updated.id()) ? updated : site).toList());
  }
  private Site withReport(Site site, Report report, List<MonitorWorkspace.Page> baseline,
      boolean verified) {
    List<Report> reports = new ArrayList<>(); reports.add(report);
    reports.addAll(site.reports().stream().limit(9).toList());
    return new Site(site.id(), site.label(), site.origin(), site.paths(), site.token(), site.demo(),
        verified ? report.checkedOn() : null, site.lastAttempt(),
        baseline == null ? site.baselineOn() : report.checkedOn(),
        baseline == null ? site.baseline() : baseline, reports);
  }
  private record Due(String owner, Site site) {}
}
