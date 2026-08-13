package dev.christopherbell.account.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.christopherbell.admin.activity.ModerationAuditCommand;
import dev.christopherbell.federation.identity.FederationIdentity;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;

/**
 * Represents a user account in the system.
 *
 * <p>
 * This class is persisted as the {@code account} kind in the shared accounts collection.
 * It includes fields for user information, authentication details,
 * and account status. Sensitive information like password hash and
 * salt are included here but should be handled carefully in application logic.
 * </p>
 */
@AllArgsConstructor
@Builder
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
public class Account {
  public static final String PROPERTY_ROLE = "role";
  private final String type = "account";

  @Id
  private String id;

  @Version
  private Long version;

  @CreatedBy
  private String createdBy;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  @CreatedDate
  private Instant createdOn;

  @Indexed(unique = true)
  private String email;
  private Boolean federationEnabled;
  private Instant federationEnabledOn;
  @JsonIgnore private FederationIdentity federationIdentity;
  private String firstName;
  private UUID inviteCode;
  private UUID inviteCodeOwner;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant lastLoginOn;
  private String lastName;

  @LastModifiedBy
  private String lastModifiedBy;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  @LastModifiedDate
  private Instant lastUpdatedOn;

  private String loginToken;
  private String passwordSalt;
  private String passwordHash;
  @Indexed
  private String passwordResetTokenHash;
  private Instant passwordResetTokenExpiresOn;
  private Role role;
  @Builder.Default
  private Set<AccountPermission> permissions = new HashSet<>();
  private AccountStatus status;
  @JsonIgnore private ModerationAuditCommand pendingModerationAudit;
  @Indexed(unique = true)
  private String username;

  /** Treats historical null values as explicitly disabled. */
  public boolean isFederationEnabled() {
    return Boolean.TRUE.equals(federationEnabled);
  }
}
