package dev.christopherbell.report;

import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for post reports.
 */
public interface ReportRepository extends MongoRepository<PostReport, String> {
  List<PostReport> findByStatusOrderByCreatedOnDesc(ReportStatus status);
  List<PostReport> findAllByOrderByCreatedOnDesc();
  long countByReportedAccountIdAndStatus(String reportedAccountId, ReportStatus status);
}
