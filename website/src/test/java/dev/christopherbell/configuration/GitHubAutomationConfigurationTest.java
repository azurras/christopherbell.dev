package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class GitHubAutomationConfigurationTest {
  private static final Pattern IMMUTABLE_ACTION = Pattern.compile("^[^@\\s]+@[0-9a-f]{40}$");
  private static final String CHECKOUT =
      "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1";
  private static final String SETUP_GRADLE =
      "gradle/actions/setup-gradle@3f131e8634966bd73d06cc69884922b02e6faf92";
  private static final String UPLOAD_ARTIFACT =
      "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a";
  private static final String DOWNLOAD_ARTIFACT =
      "actions/download-artifact@37930b1c2abaa49bbe596cd826c3c89aef350131";
  private static final String CODEQL_INIT =
      "github/codeql-action/init@e4fba868fa4b1b91e1fdab776edc8cfbe6e9fb81";
  private static final String CODEQL_ANALYZE =
      "github/codeql-action/analyze@e4fba868fa4b1b91e1fdab776edc8cfbe6e9fb81";
  private static final String DEPENDENCY_REVIEW =
      "actions/dependency-review-action@a1d282b36b6f3519aa1f3fc636f609c47dddb294";
  private static final String STALE =
      "actions/stale@1e223db275d687790206a7acac4d1a11bd6fe629";
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

  @Test
  void everyWorkflowActionUsesAFullCommitSha() throws IOException {
    var actionReferences = new ArrayList<String>();
    try (var workflows = Files.list(REPOSITORY_ROOT.resolve(".github/workflows"))) {
      for (var workflow : workflows
          .filter(GitHubAutomationConfigurationTest::isYaml)
          .sorted()
          .toList()) {
        actionReferences.addAll(YAML.readTree(workflow.toFile()).findValuesAsText("uses"));
      }
    }

    assertThat(actionReferences)
        .isNotEmpty()
        .allMatch(reference -> IMMUTABLE_ACTION.matcher(reference).matches());
  }

  @Test
  void ciCachesGradleAndRetainsFailedReports() throws IOException {
    var workflow = readYaml(".github/workflows/ci.yml");
    var steps = workflow.at("/jobs/build/steps");
    var setupGradle = stepUsing(steps, SETUP_GRADLE);
    assertThat(setupGradle.at("/with/cache-read-only").asText())
        .isEqualTo("${{ github.ref != 'refs/heads/main' }}");

    var upload = stepUsing(steps, UPLOAD_ARTIFACT);
    assertThat(upload.path("if").asText()).isEqualTo("failure()");
    assertThat(upload.at("/with/retention-days").asInt()).isEqualTo(14);
    assertThat(upload.at("/with/path").asText())
        .contains("**/build/reports/tests/**", "**/build/test-results/**");
  }

  @Test
  void ciGeneratesJooqSourcesForTheExactCommitBeforeEveryPlatformBuild() throws IOException {
    var workflow = readYaml(".github/workflows/ci.yml");
    var codegen = workflow.at("/jobs/jooq-codegen");
    var codegenSteps = codegen.path("steps");
    var build = workflow.at("/jobs/build");
    var buildSteps = build.path("steps");

    assertThat(codegen.at("/services/postgres/image").asText()).isEqualTo("postgres:18.4");
    var disposablePassword =
        "${{ format('ci-only-{0}-{1}', github.run_id, github.run_attempt) }}";
    assertThat(codegen.at("/services/postgres/env/POSTGRES_PASSWORD").asText())
        .isEqualTo(disposablePassword);
    assertThat(stepNamed(codegenSteps, "Generate jOOQ sources").path("run").asText())
        .isEqualTo("./gradlew :website:jooqCodegen");
    assertThat(stepNamed(codegenSteps, "Generate jOOQ sources")
        .at("/env/JOOQ_CODEGEN_JDBC_URL").asText())
        .isEqualTo("jdbc:postgresql://127.0.0.1:5432/test");
    assertThat(stepNamed(codegenSteps, "Generate jOOQ sources")
        .at("/env/JOOQ_CODEGEN_PASSWORD").asText())
        .isEqualTo(disposablePassword);
    assertThat(stepUsing(codegenSteps, UPLOAD_ARTIFACT).at("/with/name").asText())
        .isEqualTo("jooq-generated-${{ github.sha }}");
    assertThat(stepUsing(codegenSteps, UPLOAD_ARTIFACT).at("/with/if-no-files-found").asText())
        .isEqualTo("error");

    assertThat(build.path("needs").asText()).isEqualTo("jooq-codegen");
    var download = stepUsing(buildSteps, DOWNLOAD_ARTIFACT);
    assertThat(download.at("/with/name").asText())
        .isEqualTo("jooq-generated-${{ github.sha }}");
    assertThat(download.at("/with/path").asText())
        .isEqualTo("website/build/generated-src/jooq/main");
  }

  @Test
  void websiteTestWorkerHasEnoughHeapForTheFullRepositorySuite() throws IOException {
    var websiteBuild = Files.readString(REPOSITORY_ROOT.resolve("website/build.gradle.kts"));

    assertThat(websiteBuild)
        .contains("tasks.withType<Test>().configureEach")
        .contains("maxHeapSize = \"2g\"");
  }

  @Test
  void ciRunsPinnedWindowsPesterAndRetainsItsNunitResults() throws IOException {
    var workflow = readYaml(".github/workflows/ci.yml");
    var steps = workflow.at("/jobs/build/steps");
    var install = stepNamed(steps, "Install Pester 5.9.0");

    assertThat(install.path("if").asText()).isEqualTo("runner.os == 'Windows'");
    assertThat(install.path("timeout-minutes").asInt()).isEqualTo(5);
    assertThat(install.path("run").asText())
        .contains("Install-Module", "-RequiredVersion 5.9.0", "Import-Module Pester");
    assertThat(stepNamed(steps, "Build and Test on Windows").path("run").asText())
        .contains("gradlew.bat build");
    assertThat(stepUsing(steps, UPLOAD_ARTIFACT).at("/with/path").asText())
        .contains("**/build/test-results/shared-folder-pester/*.xml");
  }

  @Test
  void ciCancelsOnlySupersededPullRequestsAndBoundsWork() throws IOException {
    var workflow = readYaml(".github/workflows/ci.yml");
    var build = workflow.at("/jobs/build");
    var steps = build.path("steps");

    assertThat(workflow.at("/concurrency/group").asText())
        .isEqualTo("${{ github.workflow }}-${{ github.event_name == 'pull_request' && "
            + "github.event.pull_request.number || format('{0}-{1}', github.ref, github.run_id) }}");
    assertThat(workflow.at("/concurrency/cancel-in-progress").asText())
        .isEqualTo("${{ github.event_name == 'pull_request' }}");
    assertThat(build.path("timeout-minutes").asInt()).isEqualTo(30);
    assertThat(build.at("/strategy/fail-fast").asBoolean()).isFalse();
    assertThat(stepNamed(steps, "Build and Test").path("timeout-minutes").asInt()).isEqualTo(20);
    assertThat(stepNamed(steps, "Build and Test on Windows")
        .path("timeout-minutes").asInt()).isEqualTo(20);
    assertThat(stepNamed(steps, "Upload failed test reports")
        .path("timeout-minutes").asInt()).isEqualTo(5);
    assertThat(List.of("Checkout code", "Set up JDK", "Set up Node.js", "Set up Gradle"))
        .allSatisfy(name -> assertThat(stepNamed(steps, name).path("timeout-minutes").asInt())
            .isEqualTo(5));
  }

  @Test
  void codeQlPreservesAllDefaultSetupLanguagesAndBuildsJava() throws IOException {
    var workflow = readYaml(".github/workflows/codeql.yml");
    assertThat(workflow.at("/permissions/contents").asText()).isEqualTo("read");
    assertThat(workflow.at("/permissions/security-events").asText()).isEqualTo("write");
    assertThat(workflow.at("/on/pull_request/branches/0").asText()).isEqualTo("main");
    assertThat(workflow.at("/on/push/branches/0").asText()).isEqualTo("main");
    assertThat(workflow.at("/on/schedule/0/cron").asText()).isNotBlank();

    var configurations = workflow.at("/jobs/analyze/strategy/matrix/include");
    assertThat(textValues(configurations, "language"))
        .containsExactlyInAnyOrder("java-kotlin", "javascript-typescript", "actions");
    assertThat(entryFor(configurations, "language", "java-kotlin").path("build-mode").asText())
        .isEqualTo("manual");
    assertThat(entryFor(configurations, "language", "javascript-typescript")
        .path("build-mode").asText()).isEqualTo("none");
    assertThat(entryFor(configurations, "language", "actions").path("build-mode").asText())
        .isEqualTo("none");

    var steps = workflow.at("/jobs/analyze/steps");
    assertThat(stepUsing(steps, CODEQL_INIT)
        .at("/with/languages").asText()).isEqualTo("${{ matrix.language }}");
    assertThat(workflow.at("/jobs/analyze/services/postgres/image").asText())
        .isEqualTo("postgres:18.4");
    var disposablePassword =
        "${{ format('ci-only-{0}-{1}', github.run_id, github.run_attempt) }}";
    assertThat(workflow.at("/jobs/analyze/services/postgres/env/POSTGRES_PASSWORD").asText())
        .isEqualTo(disposablePassword);
    var javaBuild = stepRunning(steps, "./gradlew :website:jooqCodegen :website:classes");
    assertThat(javaBuild.path("if").asText())
        .isEqualTo("matrix.language == 'java-kotlin'");
    assertThat(javaBuild.at("/env/JOOQ_CODEGEN_JDBC_URL").asText())
        .isEqualTo("jdbc:postgresql://127.0.0.1:5432/test");
    assertThat(javaBuild.at("/env/JOOQ_CODEGEN_PASSWORD").asText())
        .isEqualTo(disposablePassword);
    assertThat(stepUsing(steps, CODEQL_ANALYZE).isMissingNode()).isFalse();
  }

  @Test
  void dependencyReviewBlocksHighSeverityVulnerableAdditions() throws IOException {
    var workflow = readYaml(".github/workflows/dependency-review.yml");
    assertThat(workflow.at("/permissions/contents").asText()).isEqualTo("read");
    assertThat(workflow.path("permissions").has("write")).isFalse();
    assertThat(workflow.at("/on/pull_request/branches/0").asText()).isEqualTo("main");
    var review = stepUsing(
        workflow.at("/jobs/dependency-review/steps"),
        DEPENDENCY_REVIEW);
    assertThat(stepUsing(
        workflow.at("/jobs/dependency-review/steps"),
        CHECKOUT).isMissingNode()).isFalse();
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

    var options = stepUsing(job.path("steps"), STALE).path("with");
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

  private static boolean isYaml(Path path) {
    var name = path.getFileName().toString();
    return name.endsWith(".yml") || name.endsWith(".yaml");
  }

  private static JsonNode stepUsing(JsonNode steps, String action) {
    return StreamSupport.stream(steps.spliterator(), false)
        .filter(step -> action.equals(step.path("uses").asText()))
        .findFirst()
        .orElse(MissingNode.getInstance());
  }

  private static JsonNode stepNamed(JsonNode steps, String name) {
    return StreamSupport.stream(steps.spliterator(), false)
        .filter(step -> name.equals(step.path("name").asText()))
        .findFirst()
        .orElse(MissingNode.getInstance());
  }

  private static JsonNode updateFor(JsonNode updates, String ecosystem) {
    return StreamSupport.stream(updates.spliterator(), false)
        .filter(update -> ecosystem.equals(update.path("package-ecosystem").asText()))
        .findFirst()
        .orElse(MissingNode.getInstance());
  }

  private static JsonNode entryFor(JsonNode entries, String field, String expected) {
    return StreamSupport.stream(entries.spliterator(), false)
        .filter(entry -> expected.equals(entry.path(field).asText()))
        .findFirst()
        .orElse(MissingNode.getInstance());
  }

  private static JsonNode stepRunning(JsonNode steps, String command) {
    return StreamSupport.stream(steps.spliterator(), false)
        .filter(step -> command.equals(step.path("run").asText()))
        .findFirst()
        .orElse(MissingNode.getInstance());
  }

  private static List<String> textValues(JsonNode values) {
    return StreamSupport.stream(values.spliterator(), false).map(JsonNode::asText).toList();
  }

  private static List<String> textValues(JsonNode values, String field) {
    return StreamSupport.stream(values.spliterator(), false)
        .map(value -> value.path(field).asText())
        .toList();
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
