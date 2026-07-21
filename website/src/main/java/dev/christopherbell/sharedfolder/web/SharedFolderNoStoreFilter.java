package dev.christopherbell.sharedfolder.web;

import static dev.christopherbell.libs.api.APIVersion.V20260717;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Prevents browser and intermediary caches from retaining any protected shared-folder response. */
public class SharedFolderNoStoreFilter extends OncePerRequestFilter {
  private static final String SHARED_API_PREFIX = "/api/shared-folder" + V20260717 + "/";
  private static final String ACCOUNT_API_PREFIX = "/api/accounts" + V20260717 + "/";
  private static final String NO_STORE = "private, no-store";
  private final SharedFolderAuditRecorder audit;

  public SharedFolderNoStoreFilter() {
    this(null);
  }

  @Autowired
  public SharedFolderNoStoreFilter(SharedFolderAuditRecorder audit) {
    this.audit = audit;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return !uri.startsWith(SHARED_API_PREFIX) && !isPermissionRequest(uri, request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    RequestAttributes previous = RequestContextHolder.getRequestAttributes();
    ServletRequestAttributes bound = previous == null
        ? new ServletRequestAttributes(request) : null;
    if (bound != null) RequestContextHolder.setRequestAttributes(bound);
    try {
      response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
      AuditAttempt attempt = attempt(request);
      try {
        filterChain.doFilter(request, response);
      } catch (ServletException | IOException | RuntimeException failure) {
        recordRejected(attempt, "failure");
        throw failure;
      }
      if (response.getStatus() >= 400) {
        recordRejected(attempt, failureCategory(response.getStatus()));
      }
    } finally {
      if (bound != null) {
        bound.requestCompleted();
        RequestContextHolder.resetRequestAttributes();
      }
    }
  }

  private AuditAttempt attempt(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String method = request.getMethod();
    if (isPermissionRequest(uri, method)) {
      return new AuditAttempt("PERMISSION_CHANGE", "account-permissions");
    }
    String path = uri.substring(SHARED_API_PREFIX.length());
    if (path.equals("entries") && method.equals("GET")) {
      return new AuditAttempt("LIST", request.getParameter("path"));
    }
    if (path.equals("content")) {
      return new AuditAttempt("DOWNLOAD_STARTED", request.getParameter("path"));
    }
    if (path.equals("preview")) {
      return new AuditAttempt("PREVIEW_STARTED", request.getParameter("path"));
    }
    if (path.equals("folders")) return new AuditAttempt("CREATE_FOLDER", "request");
    if (path.equals("entries/rename")) return new AuditAttempt("RENAME", "request");
    if (path.equals("entries/move")) return new AuditAttempt("MOVE", "request");
    if (path.equals("entries") && method.equals("DELETE")) {
      return new AuditAttempt("RECYCLE", "request");
    }
    if (path.equals("uploads")) return new AuditAttempt("UPLOAD_START", "request");
    if (path.matches("uploads/[^/]+/chunks/[^/]+")) {
      return new AuditAttempt("UPLOAD_APPEND", "upload");
    }
    if (path.matches("uploads/[^/]+/complete")) {
      return new AuditAttempt("UPLOAD_FINALIZE", "upload");
    }
    if (path.matches("uploads/[^/]+")) {
      return new AuditAttempt(method.equals("PUT") ? "UPLOAD_APPEND"
          : method.equals("DELETE") ? "UPLOAD_CANCEL" : "UPLOAD_STATUS", "upload");
    }
    if (path.equals("admin/audit")) return new AuditAttempt("AUDIT_BROWSE", "audit");
    if (path.equals("admin/recycle")) return new AuditAttempt("RECYCLE_BROWSE", "recycle");
    if (path.matches("admin/recycle/[^/]+/restore")) {
      return new AuditAttempt("RESTORE", "recycle-item");
    }
    if (path.matches("admin/recycle/[^/]+")) {
      return new AuditAttempt("PURGE", "recycle-item");
    }
    return new AuditAttempt("REQUEST", "shared-folder");
  }

  private boolean isPermissionRequest(String uri, String method) {
    return "PATCH".equals(method) && uri.startsWith(ACCOUNT_API_PREFIX)
        && uri.substring(ACCOUNT_API_PREFIX.length())
            .matches("[^/]+/shared-folder-permissions");
  }

  private String failureCategory(int status) {
    return switch (status) {
      case 400 -> "invalid_request";
      case 401, 403 -> "access_denied";
      case 404 -> "not_found";
      case 409 -> "conflict";
      case 413 -> "too_large";
      case 416 -> "invalid_range";
      case 429 -> "rate_limited";
      case 503 -> "unavailable";
      case 507 -> "insufficient_storage";
      default -> "failure";
    };
  }

  private void recordRejected(AuditAttempt attempt, String category) {
    if (audit != null
        && !audit.currentRequestAlreadyRecorded(attempt.action(), "rejected")) {
      if ("rate_limited".equals(category)) {
        audit.recordRejectedOnce(attempt.action(), attempt.resource(), category);
      } else {
        audit.recordRejected(attempt.action(), attempt.resource(), category);
      }
    }
  }

  private record AuditAttempt(String action, String resource) {}
}
