package dev.christopherbell.report;

import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

/**
 * Repository for post reports.
 */
public interface ReportRepository {
  PostReport save(PostReport report);
  Optional<PostReport> findById(String id);
  List<PostReport> findByStatusOrderByCreatedOnDesc(ReportStatus status);
  List<PostReport> findAllByOrderByCreatedOnDesc();
  List<PostReport> findAllByOrderByCreatedOnDesc(Pageable pageable);
  Optional<PostReport> findByOpenDedupeKey(String openDedupeKey);
  Optional<PostReport> findFirstByReporterAccountIdAndPostIdAndStatus(
      String reporterAccountId,
      String postId,
      ReportStatus status);
  long countByReportedAccountIdAndStatus(String reportedAccountId, ReportStatus status);
}
