package dev.christopherbell.account.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
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
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Represents a user account in the system.
 *
 * <p>
 * This class is mapped to the "accounts" collection in MongoDB.
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
@Document("accounts")
public class Account {
  public static final String PROPERTY_ROLE = "role";
  private final String type = "account";

  @Id
  private String id;
  private String approvedBy;

  @CreatedBy
  private String createdBy;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  @CreatedDate
  private Instant createdOn;

  @Indexed(unique = true)
  private String email;
  private String firstName;
  private Boolean isApproved;
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
  private AccountStatus status;
  private Set<String> followingIds;

  @Indexed(unique = true)
  private String username;
}
