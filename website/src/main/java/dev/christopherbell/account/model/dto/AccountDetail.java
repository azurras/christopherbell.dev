package dev.christopherbell.account.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for returning account details without sensitive information like password hash and salt.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class AccountDetail {
  private String id;
  private String approvedBy;
  private String createdBy;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant createdOn;

  private String email;
  private String firstName;
  private Boolean isApproved;
  private String lastName;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant lastLoginOn;

  private String lastModifiedBy;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant lastUpdatedOn;

  private Role role;
  @Builder.Default
  private Set<AccountPermission> permissions = new HashSet<>();
  private AccountStatus status;
  private String type;
  private String username;
}
