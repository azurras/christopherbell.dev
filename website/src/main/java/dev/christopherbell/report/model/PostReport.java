package dev.christopherbell.report.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.christopherbell.admin.activity.ModerationAuditCommand;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

/**
 * MongoDB document representing a report against a post.
 */
@AllArgsConstructor
@Builder
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@CompoundIndexes({
    @CompoundIndex(name = "report_created_id_desc", def = "{'createdOn': -1, '_id': -1}"),
    @CompoundIndex(name = "report_status_created_id_desc", def = "{'status': 1, 'createdOn': -1, '_id': -1}")
})
public class PostReport {
  @Id private String id;

  private String postId;
  private String postText;
  private String reportedAccountId;
  private String reportedUsername;

  private String reporterAccountId;
  private String reporterUsername;

  @Indexed(unique = true, sparse = true)
  private String openDedupeKey;
  @Indexed private ReportType reportType;
  @Indexed private ReportTargetType targetType;

  private String reason;
  private String details;
  private ReportStatus status;
  private ReportResolution resolution;
  private String resolvedBy;
  private Long openReportsForAccount;
  private Long resolvedReportsForAccount;
  @JsonIgnore private ModerationAuditCommand pendingModerationAudit;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  @CreatedDate
  private Instant createdOn;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  @LastModifiedDate
  private Instant lastUpdatedOn;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  private Instant resolvedOn;
}
