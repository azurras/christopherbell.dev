package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class GitHubAutomationConfigurationTest {
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

  @Test
  void ciCachesGradleAndRetainsFailedReports() throws IOException {
    var workflow = readYaml(".github/workflows/ci.yml");
    var steps = workflow.at("/jobs/build/steps");
    var setupGradle = stepUsing(steps, "gradle/actions/setup-gradle@v6");
    assertThat(setupGradle.at("/with/cache-read-only").asText())
        .isEqualTo("${{ github.ref != 'refs/heads/main' }}");

    var upload = stepUsing(steps, "actions/upload-artifact@v7");
    assertThat(upload.path("if").asText()).isEqualTo("failure()");
    assertThat(upload.at("/with/retention-days").asInt()).isEqualTo(14);
    assertThat(upload.at("/with/path").asText())
        .contains("**/build/reports/tests/**", "**/build/test-results/**");
  }

  @Test
  void codeQlScansJavaWithNarrowPermissions() throws IOException {
    var workflow = readYaml(".github/workflows/codeql.yml");
    assertThat(workflow.at("/permissions/contents").asText()).isEqualTo("read");
    assertThat(workflow.at("/permissions/security-events").asText()).isEqualTo("write");
    assertThat(workflow.at("/on/pull_request/branches/0").asText()).isEqualTo("main");
    assertThat(workflow.at("/on/push/branches/0").asText()).isEqualTo("main");
    assertThat(workflow.at("/on/schedule/0/cron").asText()).isNotBlank();

    var steps = workflow.at("/jobs/analyze/steps");
    assertThat(stepUsing(steps, "github/codeql-action/init@v4")
        .at("/with/languages").asText()).isEqualTo("java-kotlin");
    assertThat(stepUsing(steps, "github/codeql-action/analyze@v4").isMissingNode()).isFalse();
  }

  @Test
  void dependencyReviewBlocksHighSeverityVulnerableAdditions() throws IOException {
    var workflow = readYaml(".github/workflows/dependency-review.yml");
    assertThat(workflow.at("/permissions/contents").asText()).isEqualTo("read");
    assertThat(workflow.path("permissions").has("write")).isFalse();
    assertThat(workflow.at("/on/pull_request/branches/0").asText()).isEqualTo("main");
    var review = stepUsing(
        workflow.at("/jobs/dependency-review/steps"),
        "actions/dependency-review-action@v5");
    assertThat(stepUsing(
        workflow.at("/jobs/dependency-review/steps"),
        "actions/checkout@v7").isMissingNode()).isFalse();
    assertThat(review.at("/with/fail-on-severity").asText()).isEqualTo("high");
  }

  @Test
  void dependabotGroupsBothEcosystemsWithExistingLabels() throws IOException {
    var updates = readYaml(".github/dependabot.yml").path("updates");
    var gradle = updateFor(updates, "gradle");
    assertThat(gradle.path("open-pull-requests-limit").asInt()).isEqualTo(5);
    assertThat(textValues(gradle.path("labels"))).containsExactly("dependencies", "java");
    assertThat(textValues(gradle.at("/groups/spring/patterns")))
        .contains("org.springframework*");
    assertThat(textValues(gradle.at("/groups/minor-and-patch/update-types")))
        .containsExactly("minor", "patch");

    var actions = updateFor(updates, "github-actions");
    assertThat(textValues(actions.path("labels")))
        .containsExactly("dependencies", "github_actions");
    assertThat(textValues(actions.at("/groups/github-actions/patterns")))
        .containsExactly("*");
  }

  @Test
  void staleAutomationIsBoundedExemptibleAndLeastPrivilege() throws IOException {
    var workflow = readYaml(".github/workflows/stale.yml");
    var job = workflow.at("/jobs/stale");
    assertThat(workflow.path("permissions").isEmpty()).isTrue();
    assertThat(job.at("/permissions/issues").asText()).isEqualTo("write");
    assertThat(job.at("/permissions/pull-requests").asText()).isEqualTo("write");
    assertThat(job.path("permissions").has("contents")).isFalse();

    var options = stepUsing(job.path("steps"), "actions/stale@v10").path("with");
    assertThat(options.path("days-before-issue-stale").asInt()).isEqualTo(60);
    assertThat(options.path("days-before-issue-close").asInt()).isEqualTo(14);
    assertThat(options.path("days-before-pr-stale").asInt()).isEqualTo(30);
    assertThat(options.path("days-before-pr-close").asInt()).isEqualTo(14);
    assertThat(options.path("exempt-issue-labels").asText())
        .isEqualTo("pinned,roadmap,security,codex");
    assertThat(options.path("exempt-pr-labels").asText()).isEqualTo("security,codex");
    assertThat(options.path("exempt-all-issue-milestones").asBoolean()).isTrue();
    assertThat(options.path("exempt-all-issue-assignees").asBoolean()).isTrue();
    assertThat(options.path("remove-stale-when-updated").asBoolean()).isTrue();
    assertThat(options.path("stale-issue-message").asText())
        .isNotEqualTo("Stale issue message");
    assertThat(options.path("stale-pr-message").asText())
        .isNotEqualTo("Stale pull request message");
  }

  private static JsonNode readYaml(String repositoryRelativePath) throws IOException {
    var path = REPOSITORY_ROOT.resolve(repositoryRelativePath);
    return Files.exists(path) ? YAML.readTree(path.toFile()) : MissingNode.getInstance();
  }

  private static JsonNode stepUsing(JsonNode steps, String action) {
    return StreamSupport.stream(steps.spliterator(), false)
        .filter(step -> action.equals(step.path("uses").asText()))
        .findFirst()
        .orElse(MissingNode.getInstance());
  }

  private static JsonNode updateFor(JsonNode updates, String ecosystem) {
    return StreamSupport.stream(updates.spliterator(), false)
        .filter(update -> ecosystem.equals(update.path("package-ecosystem").asText()))
        .findFirst()
        .orElse(MissingNode.getInstance());
  }

  private static List<String> textValues(JsonNode values) {
    return StreamSupport.stream(values.spliterator(), false).map(JsonNode::asText).toList();
  }

  private static Path locateRepositoryRoot() {
    var current = Path.of("").toAbsolutePath().normalize();
    if (Files.isDirectory(current.resolve(".github"))) {
      return current;
    }
    var parent = current.getParent();
    if (parent != null && Files.isDirectory(parent.resolve(".github"))) {
      return parent;
    }
    throw new IllegalStateException("Cannot locate repository root from " + current);
  }
}
