"use strict";

const manifest = DomainCollectionManifest.MANIFEST;
const migration = DomainCollectionMigration;
const digest = DomainCollectionManifest.DIGEST;
const owner = "0123456789abcdef0123456789abcdef";
const wrongOwner = "fedcba9876543210fedcba9876543210";
const release = "a".repeat(40);
const backupIdentity = "b".repeat(64);
const protectedEvidence = new Map();
const databases = Object.freeze({
  main: "cbell_candidate_aaaaaaaaaaaa_aaaaaaaaaaaaaaaaaaaaaaaa",
  restore: "cbell_candidate_bbbbbbbbbbbb_bbbbbbbbbbbbbbbbbbbbbbbb",
  malformed: "cbell_candidate_cccccccccccc_cccccccccccccccccccccccc",
  stale: "cbell_candidate_dddddddddddd_dddddddddddddddddddddddd",
  collision: "cbell_candidate_eeeeeeeeeeee_eeeeeeeeeeeeeeeeeeeeeeee",
  unexpected: "cbell_candidate_ffffffffffff_ffffffffffffffffffffffff",
  optional: "cbell_candidate_111111111111_111111111111111111111111",
  malformedStage: "cbell_candidate_222222222222_222222222222222222222222",
  required: "cbell_candidate_333333333333_333333333333333333333333",
  v014: "cbell_candidate_444444444444_444444444444444444444444",
  corruptPlan: "cbell_candidate_555555555555_555555555555555555555555",
  faultMatrix: "cbell_candidate_666666666666_666666666666666666666666",
  ledgerPublish: "cbell_candidate_777777777777_777777777777777777777777",
  ledgerLive: "cbell_candidate_888888888888_888888888888888888888888",
  ledgerDrop: "cbell_candidate_999999999999_999999999999999999999999",
  rawDrift: "cbell_candidate_abababababab_abababababababababababab",
  reverseDocument: "cbell_candidate_acacacacacac_acacacacacacacacacacacac",
  reverseIndex: "cbell_candidate_adadadadadad_adadadadadadadadadadadad",
  replacement: "cbell_candidate_aeaeaeaeaeae_aeaeaeaeaeaeaeaeaeaeaeae",
  dropIntentDrift: "cbell_candidate_afafafafafaf_afafafafafafafafafafafaf",
  recoveryInit: "cbell_candidate_b0b0b0b0b0b0_b0b0b0b0b0b0b0b0b0b0b0b0",
  recoveryPartialStage: "cbell_candidate_b1b1b1b1b1b1_b1b1b1b1b1b1b1b1b1b1b1b1",
  recoveryStaged: "cbell_candidate_b2b2b2b2b2b2_b2b2b2b2b2b2b2b2b2b2b2b2",
  recoveryPublishing: "cbell_candidate_b3b3b3b3b3b3_b3b3b3b3b3b3b3b3b3b3b3b3",
  recoveryLiveDrift: "cbell_candidate_b4b4b4b4b4b4_b4b4b4b4b4b4b4b4b4b4b4b4"
});

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function command(database, action, commandOwner = owner) {
  globalThis.DOMAIN_COLLECTION_EVIDENCE = protectedEvidence.get(database) || null;
  const expectedEvidenceDigest = action === "preview" ? "0".repeat(64)
    : migration.evidenceDigest(globalThis.DOMAIN_COLLECTION_EVIDENCE);
  let outcome;
  try {
    outcome = migration.execute(
      db, [database, action, digest, commandOwner, release, backupIdentity,
        expectedEvidenceDigest]);
  } catch (failure) {
    throw new Error(`${database} ${action}: ${failure.message}`);
  }
  if (action === "preview") {
    assert(outcome.evidence !== null, "preview omitted protected evidence");
    protectedEvidence.set(database, outcome.evidence);
  } else {
    assert(outcome.evidence === null, action + " exposed protected evidence");
  }
  assert(outcome.database === database, action + " omitted its database");
  assert(outcome.action === action, action + " omitted its action");
  assert(outcome.manifestDigest === digest, action + " omitted its manifest digest");
  assert(outcome.backupIdentity === backupIdentity, action + " omitted its backup identity");
  assert(outcome.expectedEvidenceDigest === (action === "preview"
    ? outcome.evidenceDigest : expectedEvidenceDigest),
  action + " omitted its independently supplied evidence digest");
  assert(typeof outcome.evidenceDigest === "string", action + " omitted its evidence digest");
  assert(outcome.kinds.length === manifest.kinds.length, action + " omitted kind evidence");
  assert(outcome.indexes.length === manifest.targets.length, action + " omitted index evidence");
  return outcome;
}

function seed(databaseName, v014Order = "durable") {
  assert(["durable", "fresh"].includes(v014Order), "V014 fixture order is invalid");
  const database = db.getSiblingDB(databaseName);
  database.dropDatabase();
  for (const kind of manifest.kinds) {
    if (!kind.source) continue;
    let id = kind.sourceId || kind.kind + "-id";
    if (kind.kind === "account") id = ObjectId("64b64b64b64b64b64b64b64b");
    if (kind.kind === "migration_record") id = "014-consolidate-music-runtime-state";
    let document = {
      _id: id,
      marker: kind.kind,
      longValue: Long.fromString("9007199254740993"),
      decimalValue: Decimal128.fromString("1234567890.0123456789"),
      intValue: new Int32(7),
      sameValueLong: Long.fromString("7"),
      doubleValue: new Double(7.25),
      recordedAt: ISODate("2026-08-11T00:00:00.000Z"),
      nested: { z: 2, a: 1 }
    };
    if (kind.kind === "migration_record") {
      document = v014Order === "durable"
        ? {
            _id: "014-consolidate-music-runtime-state",
            checksum: "11a69bdd4556cfc38060ccdda5075fb9d6bc36f1cc414edd7b26cd61a74b5cbb",
            description: "Consolidate Music queue and radio runtime state",
            status: "APPLIED",
            ownerToken: "v014-owner",
            startedAt: ISODate("2026-08-10T00:00:00.000Z"),
            _class: "dev.christopherbell.configuration.mongo.migration.MigrationRecord",
            completedAt: ISODate("2026-08-10T00:01:00.000Z")
          }
        : {
            _id: "014-consolidate-music-runtime-state",
            checksum: "11a69bdd4556cfc38060ccdda5075fb9d6bc36f1cc414edd7b26cd61a74b5cbb",
            description: "Consolidate Music queue and radio runtime state",
            status: "APPLIED",
            ownerToken: "v014-owner",
            startedAt: ISODate("2026-08-10T00:00:00.000Z"),
            completedAt: ISODate("2026-08-10T00:01:00.000Z"),
            _class: "dev.christopherbell.configuration.mongo.migration.MigrationRecord"
          };
    }
    database.getCollection(kind.source).insertOne(document);
  }
  database.getCollection("accounts").insertMany([
    { _id: BinData(0, "AQID"), marker: "binary-id", email: "binary@example.test",
      username: "binary-account" },
    { _id: UUID("00112233-4455-6677-8899-aabbccddeeff"), marker: "uuid-id",
      email: "uuid@example.test", username: "uuid-account" }
  ]);
  for (const name of manifest.dropOnly) {
    database.getCollection(name).insertOne({ _id: name, retained: true });
  }
  return database;
}

function stageAll(databaseName) {
  let outcome;
  let prior = 0;
  for (let count = 0; count < manifest.kinds.length + manifest.targets.length; count++) {
    outcome = command(databaseName, "stage");
    const ledger = db.getSiblingDB(databaseName)
      .getCollection("__domain_stage__application_migrations")
      .findOne({ _kind: "domain_collection_cutover" });
    assert(ledger.payload.stageIndex === prior + 1, "staging advanced by more than one operation");
    prior = ledger.payload.stageIndex;
  }
  assert(outcome.complete && outcome.state === "STAGED", "staging did not complete exactly");
  const revision = db.getSiblingDB(databaseName)
    .getCollection("__domain_stage__application_migrations")
    .findOne({ _kind: "domain_collection_cutover" }).payload.revision;
  assert(command(databaseName, "stage").complete, "completed staging retry was not idempotent");
  assert(db.getSiblingDB(databaseName).getCollection("__domain_stage__application_migrations")
    .findOne({ _kind: "domain_collection_cutover" }).payload.revision === revision,
  "completed staging retry mutated the ledger");
}

function rawRename(databaseName, from, to) {
  const outcome = db.getSiblingDB("admin").runCommand({
    renameCollection: databaseName + "." + from,
    to: databaseName + "." + to,
    dropTarget: false
  });
  assert(outcome.ok === 1, "crash-boundary rename simulation failed");
}

function setIndexHidden(database, collection, index, hidden) {
  const outcome = database.runCommand({
    collMod: collection,
    index: { name: index, hidden }
  });
  assert(outcome.ok === 1, "collMod hidden-state test setup failed");
}

function publishAll(databaseName) {
  let prior = 0;
  while (true) {
    const outcome = command(databaseName, "publish-next");
    const database = db.getSiblingDB(databaseName);
    const ledger = database.getCollection("__domain_stage__application_migrations")
      .findOne({ _kind: "domain_collection_cutover" })
      || database.getCollection("application_migrations")
        .findOne({ _kind: "domain_collection_cutover" });
    assert(ledger.payload.publishIndex === prior + 1, "publication advanced by more than one rename");
    prior = ledger.payload.publishIndex;
    if (outcome.complete) {
      const revision = ledger.payload.revision;
      assert(command(databaseName, "publish-next").complete,
        "completed publication retry was not idempotent");
      assert(database.getCollection("application_migrations")
        .findOne({ _kind: "domain_collection_cutover" }).payload.revision === revision,
      "completed publication retry mutated the ledger");
      return prior;
    }
  }
}

function reverseAll(databaseName) {
  const database = db.getSiblingDB(databaseName);
  let ledger = database.getCollection("__domain_stage__application_migrations")
    .findOne({ _kind: "domain_collection_cutover" })
    || database.getCollection("application_migrations")
      .findOne({ _kind: "domain_collection_cutover" });
  let prior = ledger.payload.publishIndex;
  while (true) {
    const outcome = command(databaseName, "reverse-next");
    ledger = database.getCollection("__domain_stage__application_migrations")
      .findOne({ _kind: "domain_collection_cutover" })
      || database.getCollection("application_migrations")
        .findOne({ _kind: "domain_collection_cutover" });
    assert(ledger.payload.publishIndex === prior - 1,
      "reversal advanced by more than one operation");
    prior = ledger.payload.publishIndex;
    if (outcome.complete) {
      const revision = ledger.payload.revision;
      assert(command(databaseName, "reverse-next").complete,
        "completed reversal retry was not idempotent");
      assert(database.getCollection("__domain_stage__application_migrations")
        .findOne({ _kind: "domain_collection_cutover" }).payload.revision === revision,
      "completed reversal retry mutated the ledger");
      return;
    }
  }
}

function expectFailure(work, label) {
  let failed = false;
  try { work(); } catch (expected) { failed = true; }
  assert(failed, label + " did not fail closed");
}

function expectV014PreviewFailure(databaseName, label, expectedMessage) {
  const database = db.getSiblingDB(databaseName);
  const collection = database.getCollection("application_migrations");
  const before = collection.findOne({ _id: "014-consolidate-music-runtime-state" });
  assert(before, label + " omitted its V014 record");
  const beforeKeys = JSON.stringify(Object.keys(before));
  const beforeDocument = EJSON.stringify(before, { relaxed: false });
  let failure = null;
  try {
    command(databaseName, "preview");
  } catch (expected) {
    failure = expected;
  }
  assert(failure, label + " did not fail closed");
  assert(failure.message.endsWith(expectedMessage),
    label + " failed with an unexpected diagnostic: " + failure.message);
  const after = collection.findOne({ _id: "014-consolidate-music-runtime-state" });
  assert(after, label + " removed its V014 record");
  assert(JSON.stringify(Object.keys(after)) === beforeKeys,
    label + " reordered its V014 record");
  assert(EJSON.stringify(after, { relaxed: false }) === beforeDocument,
    label + " mutated its V014 record");
}

function currentLedger(databaseName) {
  const database = db.getSiblingDB(databaseName);
  return database.getCollection("__domain_stage__application_migrations")
    .findOne({ _kind: "domain_collection_cutover" })
    || database.getCollection("application_migrations")
      .findOne({ _kind: "domain_collection_cutover" });
}

function interruptEveryBoundary(databaseName, action, progressField, expectedProgress) {
  for (const point of ["after-intent", "after-effect", "after-reconcile"]) {
    globalThis.DOMAIN_COLLECTION_INTERRUPT_AT = point;
    expectFailure(() => command(databaseName, action), action + " " + point);
    if (point === "after-intent") {
      const ledger = currentLedger(databaseName);
      const collection = db.getSiblingDB(databaseName)
        .getCollection(ledger._id.kind === "domain_collection_cutover"
          && db.getSiblingDB(databaseName).getCollection("__domain_stage__application_migrations")
            .findOne({ _id: ledger._id }) ? "__domain_stage__application_migrations"
          : "application_migrations");
      const stale = collection.updateOne({
        _id: ledger._id,
        "payload.ownerToken": owner,
        "payload.revision": ledger.payload.revision - 1,
        "payload.intent": null
      }, { $set: { "payload.state": "CORRUPT_CONCURRENT_STATE" } });
      assert(stale.matchedCount === 0,
        action + " accepted a stale same-owner concurrent write");
    }
  }
  globalThis.DOMAIN_COLLECTION_INTERRUPT_AT = null;
  const ledger = currentLedger(databaseName);
  assert(ledger.payload[progressField] === expectedProgress,
    action + " did not advance exactly once across interruption retries");
  assert(ledger.payload.intent === null, action + " retained a completed effect intent");
}

function activateTarget(databaseName) {
  stageAll(databaseName);
  command(databaseName, "verify-stage");
  publishAll(databaseName);
  command(databaseName, "verify-live");
}

function reverseUntilFinal(databaseName) {
  while (currentLedger(databaseName).payload.publishIndex > 1) {
    command(databaseName, "reverse-next");
  }
}

function recoverPrepublicationScenario(databaseName) {
  while (!command(databaseName, "recover-prepublication").complete) {
    // The engine owns one bounded allowlisted recovery effect per call.
  }
  command(databaseName, "preview");
}

for (const name of Object.values(databases)) db.getSiblingDB(name).dropDatabase();

const recoveryLiveDrift = seed(databases.recoveryLiveDrift);
recoveryLiveDrift.getCollection("scheduled_collector_runs").drop();
command(databases.recoveryLiveDrift, "preview");
recoveryLiveDrift.getCollection("accounts").updateOne(
  { marker: "account" }, { $set: { liveWriteAfterPreview: true } });
recoveryLiveDrift.getCollection("scheduled_collector_runs").insertOne({
  _id: "collector-created-after-preview", marker: "scheduled_collector_run"
});
assert(command(databases.recoveryLiveDrift, "recover-prepublication").complete,
  "no-effect prepublication recovery rejected ordinary live writes or an approved collection");
recoveryLiveDrift.getCollection("scheduled_collector_runs").deleteMany({});
recoveryLiveDrift.createCollection("__domain_stage__accounts");
expectFailure(() => command(databases.recoveryLiveDrift, "recover-prepublication"),
  "staged recovery with a new empty approved legacy collection");
assert(recoveryLiveDrift.getCollectionInfos({ name: "__domain_stage__accounts" }).length === 1,
  "failed strict recovery removed staged data");
recoveryLiveDrift.getCollection("scheduled_collector_runs").drop();
recoveryLiveDrift.getCollection("accounts").updateOne(
  { marker: "account" }, { $unset: { liveWriteAfterPreview: "" } });
assert(command(databases.recoveryLiveDrift, "recover-prepublication").complete,
  "repaired strict recovery did not remove its owned stage");

const restore = seed(databases.restore);
command(databases.restore, "preview");
assert(command(databases.restore, "restore-verify").state === "LEGACY_RESTORE_VERIFIED",
  "restore verification failed");
const restoreAccount = restore.getCollection("accounts").findOne({ marker: "account" });
restore.getCollection("accounts").updateOne(
  { _id: restoreAccount._id }, { $set: { marker: "mutated" } });
expectFailure(() => command(databases.restore, "restore-verify"), "mutated restore document");
restore.getCollection("accounts").replaceOne({ _id: restoreAccount._id }, restoreAccount);
restore.getCollection("accounts").createIndex({ unexpected: 1 });
expectFailure(() => command(databases.restore, "restore-verify"), "mutated restore index");
restore.getCollection("accounts").dropIndex("unexpected_1");
restore.createCollection("unexpected_restore_collection");
expectFailure(() => command(databases.restore, "restore-verify"), "extra restore collection");
restore.getCollection("unexpected_restore_collection").drop();
assert(command(databases.restore, "restore-verify").complete, "repaired restore did not verify");

const optional = seed(databases.optional);
optional.getCollection("scheduled_collector_runs").drop();
command(databases.optional, "preview");
assert(command(databases.optional, "preview").kinds
  .find((kind) => kind.kind === "scheduled_collector_run").count === 0,
"absent optional source was not reported as empty");
stageAll(databases.optional);
command(databases.optional, "verify-stage");

const required = seed(databases.required);
command(databases.required, "preview");
required.getCollection("scheduled_collector_runs").drop();
expectFailure(() => command(databases.required, "stage"), "protected present source removal");

const v014 = seed(databases.v014, "fresh");
command(databases.v014, "preview");
v014.getCollection("application_migrations").updateOne(
  { _id: "014-consolidate-music-runtime-state" }, { $set: { checksum: "0".repeat(64) } });
expectFailure(() => command(databases.v014, "preview"), "wrong V014 checksum");

const malformedV014Message = "Mongo V014 authority is absent or malformed.";
const v014NegativeCases = [
  {
    label: "third V014 field order",
    expectedMessage: malformedV014Message,
    mutate(database, original) {
      database.getCollection("application_migrations").replaceOne(
        { _id: original._id },
        {
          _id: original._id,
          checksum: original.checksum,
          description: original.description,
          status: original.status,
          ownerToken: original.ownerToken,
          _class: original._class,
          startedAt: original.startedAt,
          completedAt: original.completedAt
        });
    }
  },
  {
    label: "missing V014 field",
    expectedMessage: malformedV014Message,
    mutate(database) {
      database.getCollection("application_migrations").updateOne(
        { _id: "014-consolidate-music-runtime-state" }, { $unset: { completedAt: "" } });
    }
  },
  {
    label: "extra V014 field",
    expectedMessage: malformedV014Message,
    mutate(database) {
      database.getCollection("application_migrations").updateOne(
        { _id: "014-consolidate-music-runtime-state" }, { $set: { unexpected: true } });
    }
  },
  {
    label: "wrong V014 class",
    expectedMessage: malformedV014Message,
    mutate(database) {
      database.getCollection("application_migrations").updateOne(
        { _id: "014-consolidate-music-runtime-state" }, { $set: { _class: "wrong" } });
    }
  },
  {
    label: "wrong V014 BSON date type",
    expectedMessage: malformedV014Message,
    mutate(database) {
      database.getCollection("application_migrations").updateOne(
        { _id: "014-consolidate-music-runtime-state" },
        { $set: { completedAt: "2026-08-10T00:01:00.000Z" } });
    }
  },
  {
    label: "absent V014 authoritative source",
    expectedMessage: "Mongo V014 authoritative source is absent.",
    mutate(database) {
      database.getCollection("music_runtime_state").drop();
    }
  }
];
for (const scenario of v014NegativeCases) {
  const database = seed(databases.v014, "fresh");
  protectedEvidence.delete(databases.v014);
  const original = database.getCollection("application_migrations")
    .findOne({ _id: "014-consolidate-music-runtime-state" });
  scenario.mutate(database, original);
  expectV014PreviewFailure(
    databases.v014, scenario.label, scenario.expectedMessage);
}

seed(databases.replacement);
command(databases.replacement, "preview");
const originalEvidence = protectedEvidence.get(databases.replacement);
const replacementEvidence = { ...originalEvidence,
  collections: originalEvidence.collections.map((metric, index) => index === 0
    ? { ...metric, checksum: "f".repeat(64) } : metric) };
globalThis.DOMAIN_COLLECTION_EVIDENCE = replacementEvidence;
expectFailure(() => migration.execute(db, [databases.replacement, "stage", digest, owner,
  release, backupIdentity, migration.evidenceDigest(originalEvidence)]),
"replacement evidence with a recomputed digest");
globalThis.DOMAIN_COLLECTION_EVIDENCE = null;

seed(databases.ledgerPublish);
command(databases.ledgerPublish, "preview");
stageAll(databases.ledgerPublish);
command(databases.ledgerPublish, "verify-stage");
db.getSiblingDB(databases.ledgerPublish)
  .getCollection("__domain_stage__application_migrations").updateOne(
    { _kind: "domain_collection_cutover" },
    { $set: { "payload.presentSources": [] } });
expectFailure(() => command(databases.ledgerPublish, "publish-next"),
  "corrupt ledger source plan at publication");

seed(databases.ledgerLive);
command(databases.ledgerLive, "preview");
stageAll(databases.ledgerLive);
command(databases.ledgerLive, "verify-stage");
publishAll(databases.ledgerLive);
db.getSiblingDB(databases.ledgerLive).getCollection("application_migrations").updateOne(
  { _kind: "domain_collection_cutover" },
  { $set: { "payload.expectedKindMetrics.0.checksum": "f".repeat(64) } });
expectFailure(() => command(databases.ledgerLive, "verify-live"),
  "corrupt ledger metrics at live verification");

seed(databases.ledgerDrop);
command(databases.ledgerDrop, "preview");
activateTarget(databases.ledgerDrop);
db.getSiblingDB(databases.ledgerDrop).getCollection("application_migrations").updateOne(
  { _kind: "domain_collection_cutover" },
  { $set: { "payload.presentSources.0": "account_follows" } });
expectFailure(() => command(databases.ledgerDrop, "drop-legacy"),
  "corrupt ledger source plan at deletion");

const malformed = seed(databases.malformed);
malformed.getCollection("accounts").deleteMany({});
malformed.getCollection("accounts").insertOne({ _id: { nested: "unsupported" }, email: "x" });
expectFailure(() => command(databases.malformed, "preview"), "malformed source identity");

const stale = seed(databases.stale);
command(databases.stale, "preview");
stale.createCollection("__domain_stage__accounts");
expectFailure(() => command(databases.stale, "stage"), "stale target residue");

const unexpected = seed(databases.unexpected);
unexpected.createCollection("unapproved_collection");
expectFailure(() => command(databases.unexpected, "preview"), "unexpected collection");

const collision = seed(databases.collision);
command(databases.collision, "preview");
command(databases.collision, "stage");
expectFailure(() => command(databases.collision, "stage", wrongOwner), "CAS owner mismatch");
const follow = collision.getCollection("account_follows").findOne({});
collision.getCollection("__domain_stage__accounts").insertOne({
  _id: { kind: "account_follow", legacyId: follow._id },
  _kind: "account_follow",
  schemaVersion: 1,
  payload: { corrupted: true }
});
expectFailure(() => command(databases.collision, "stage"), "staging collision");

const main = seed(databases.main);
const mainPreview = command(databases.main, "preview");
assert(mainPreview.kinds.length === 52, "preview did not report all kinds");
stageAll(databases.main);
const account = main.getCollection("__domain_stage__accounts")
  .findOne({ _kind: "account" });
assert(account._id.legacyId instanceof ObjectId, "ObjectId identity type changed");
assert(account.payload.longValue instanceof Long, "int64 payload type changed");
assert(account.payload.decimalValue instanceof Decimal128, "decimal payload type changed");
assert(migration.canonicalExtendedJson(account.payload.intValue).includes("$numberInt"),
  "int32 payload type changed");
assert(migration.canonicalExtendedJson(account.payload.doubleValue).includes("$numberDouble"),
  "double payload type changed");
assert(migration.canonicalExtendedJson(account.payload.intValue)
  !== migration.canonicalExtendedJson(account.payload.sameValueLong),
"same-value distinct numeric BSON types collapsed");
assert(account.payload.recordedAt instanceof Date, "date payload type changed");
assert(migration.canonicalExtendedJson(main.getCollection("__domain_stage__accounts")
  .findOne({ "_id.legacyId": BinData(0, "AQID") })._id.legacyId).includes('"subType":"00"'),
"binary identity type changed");
assert(migration.canonicalExtendedJson(main.getCollection("__domain_stage__accounts")
  .findOne({ "_id.legacyId": UUID("00112233-4455-6677-8899-aabbccddeeff") })
  ._id.legacyId).includes('"subType":"04"'), "UUID identity type changed");
setIndexHidden(main, "__domain_stage__accounts", "account__username_asc", true);
expectFailure(() => command(databases.main, "verify-stage"), "hidden staged target index");
setIndexHidden(main, "__domain_stage__accounts", "account__username_asc", false);
const stageVerification = command(databases.main, "verify-stage");
assert(stageVerification.indexes.reduce((sum, target) => sum + target.count, 0) === 126,
  "stage index count is not exact");
let verifiedLedger = main.getCollection("__domain_stage__application_migrations")
  .findOne({ _kind: "domain_collection_cutover" });
assert(command(databases.main, "verify-stage").complete,
  "completed stage verification retry was not idempotent");
assert(main.getCollection("__domain_stage__application_migrations")
  .findOne({ _kind: "domain_collection_cutover" }).payload.revision === verifiedLedger.payload.revision,
"completed stage verification retry mutated the ledger");

const malformedStage = seed(databases.malformedStage);
command(databases.malformedStage, "preview");
stageAll(databases.malformedStage);
const malformedEnvelope = malformedStage.getCollection("__domain_stage__accounts")
  .findOne({ _kind: "account" });
malformedStage.getCollection("__domain_stage__accounts")
  .updateOne({ _id: malformedEnvelope._id }, { $set: { schemaVersion: "1" } });
expectFailure(() => command(databases.malformedStage, "verify-stage"), "malformed envelope");
malformedStage.getCollection("__domain_stage__accounts")
  .updateOne({ _id: malformedEnvelope._id }, { $set: { schemaVersion: 1 } });
malformedStage.getCollection("__domain_stage__accounts").createIndex({ unexpected: 1 });
expectFailure(() => command(databases.malformedStage, "verify-stage"), "unexpected target index");
malformedStage.getCollection("__domain_stage__accounts").dropIndex("unexpected_1");
malformedStage.getCollection("__domain_stage__accounts").insertOne({
  _id: { kind: "unknown_kind", legacyId: "unexpected" },
  _kind: "unknown_kind", schemaVersion: 1, payload: {}
});
expectFailure(() => command(databases.malformedStage, "verify-stage"), "unknown target kind");
malformedStage.getCollection("__domain_stage__accounts")
  .deleteOne({ _kind: "unknown_kind" });
command(databases.malformedStage, "verify-stage");

const corruptPlan = seed(databases.corruptPlan);
command(databases.corruptPlan, "preview");
command(databases.corruptPlan, "stage");
corruptPlan.getCollection("__domain_stage__application_migrations").updateOne(
  { _kind: "domain_collection_cutover" },
  { $set: { "payload.publicationOperations": [
    { kind: "rename", from: "accounts", to: "unrelated_collection" }
  ] } });
expectFailure(() => command(databases.corruptPlan, "stage"), "mutable publication plan");

const rawDrift = seed(databases.rawDrift);
rawDrift.getCollection("accounts").createIndex(
  { legacyLookup: 1 }, { name: "legacy_lookup" });
command(databases.rawDrift, "preview");
activateTarget(databases.rawDrift);
setIndexHidden(rawDrift, "__domain_legacy__accounts", "legacy_lookup", true);
expectFailure(() => command(databases.rawDrift, "drop-legacy"),
  "hidden ordinary legacy index before first drop intent");
setIndexHidden(rawDrift, "__domain_legacy__accounts", "legacy_lookup", false);
const protectedAccount = rawDrift.getCollection("__domain_legacy__accounts")
  .findOne({ marker: "account" });
rawDrift.getCollection("__domain_legacy__accounts").updateOne(
  { _id: protectedAccount._id }, { $set: { marker: "post-preview-drift" } });
expectFailure(() => command(databases.rawDrift, "drop-legacy"),
  "ordinary legacy document drift before deletion");
rawDrift.getCollection("__domain_legacy__accounts").replaceOne(
  { _id: protectedAccount._id }, protectedAccount);
rawDrift.getCollection("__domain_legacy__accounts").createIndex({ unexpected: 1 });
expectFailure(() => command(databases.rawDrift, "drop-legacy"),
  "ordinary legacy index drift before deletion");
rawDrift.getCollection("__domain_legacy__accounts").dropIndex("unexpected_1");
assert(command(databases.rawDrift, "drop-legacy").complete === false,
  "repaired raw legacy collection did not resume deletion");

const dropIntentDrift = seed(databases.dropIntentDrift);
dropIntentDrift.getCollection("accounts").createIndex(
  { legacyLookup: 1 }, { name: "legacy_lookup" });
command(databases.dropIntentDrift, "preview");
activateTarget(databases.dropIntentDrift);
globalThis.DOMAIN_COLLECTION_INTERRUPT_AT = "after-intent";
expectFailure(() => command(databases.dropIntentDrift, "drop-legacy"),
  "drop after-intent interruption");
globalThis.DOMAIN_COLLECTION_INTERRUPT_AT = null;
setIndexHidden(dropIntentDrift, "__domain_legacy__accounts", "legacy_lookup", true);
expectFailure(() => command(databases.dropIntentDrift, "drop-legacy"),
  "hidden legacy index after persisted drop intent");
setIndexHidden(dropIntentDrift, "__domain_legacy__accounts", "legacy_lookup", false);
const intentAccount = dropIntentDrift.getCollection("__domain_legacy__accounts")
  .findOne({ marker: "account" });
dropIntentDrift.getCollection("__domain_legacy__accounts").updateOne(
  { _id: intentAccount._id }, { $set: { marker: "after-intent-drift" } });
expectFailure(() => command(databases.dropIntentDrift, "drop-legacy"),
  "after-intent legacy mutation");
assert(dropIntentDrift.getCollection("__domain_legacy__accounts").countDocuments({}) > 0
  && currentLedger(databases.dropIntentDrift).payload.dropIndex === 0,
"after-intent drift was deleted or reconciled");
dropIntentDrift.getCollection("__domain_legacy__accounts").replaceOne(
  { _id: intentAccount._id }, intentAccount);
assert(command(databases.dropIntentDrift, "drop-legacy").complete === false,
  "repaired after-intent collection did not resume the exact drop");

const reverseDocument = seed(databases.reverseDocument);
command(databases.reverseDocument, "preview");
stageAll(databases.reverseDocument);
command(databases.reverseDocument, "verify-stage");
publishAll(databases.reverseDocument);
const reverseAccount = reverseDocument.getCollection("__domain_legacy__accounts")
  .findOne({ marker: "account" });
reverseDocument.getCollection("__domain_legacy__accounts").updateOne(
  { _id: reverseAccount._id }, { $set: { marker: "reverse-drift" } });
reverseUntilFinal(databases.reverseDocument);
expectFailure(() => command(databases.reverseDocument, "reverse-next"),
  "final reverse document proof");

const reverseIndex = seed(databases.reverseIndex);
command(databases.reverseIndex, "preview");
stageAll(databases.reverseIndex);
command(databases.reverseIndex, "verify-stage");
publishAll(databases.reverseIndex);
reverseIndex.getCollection("__domain_legacy__accounts").createIndex({ unexpected: 1 });
reverseUntilFinal(databases.reverseIndex);
expectFailure(() => command(databases.reverseIndex, "reverse-next"),
  "final reverse index proof");

globalThis.DOMAIN_COLLECTION_INTERRUPT_AT = "after-effect";
expectFailure(() => command(databases.main, "publish-next"), "publication after-effect interruption");
globalThis.DOMAIN_COLLECTION_INTERRUPT_AT = null;
assert(command(databases.main, "publish-next").state === "PUBLISHING",
  "publication did not resume after a completed rename");
for (let count = 0; count < 6; count++) command(databases.main, "publish-next");
reverseAll(databases.main);
command(databases.main, "verify-stage");
publishAll(databases.main);
command(databases.main, "verify-live");
reverseAll(databases.main);
command(databases.main, "verify-stage");
publishAll(databases.main);
setIndexHidden(main, "accounts", "account__username_asc", true);
expectFailure(() => command(databases.main, "verify-live"), "hidden live target index");
setIndexHidden(main, "accounts", "account__username_asc", false);
const live = command(databases.main, "verify-live");
assert(live.state === "TARGET_ACTIVE", "live verification did not activate target");
verifiedLedger = main.getCollection("application_migrations")
  .findOne({ _kind: "domain_collection_cutover" });
assert(command(databases.main, "verify-live").complete,
  "completed live verification retry was not idempotent");
assert(main.getCollection("application_migrations")
  .findOne({ _kind: "domain_collection_cutover" }).payload.revision === verifiedLedger.payload.revision,
"completed live verification retry mutated the ledger");

let drops = 0;
while (true) {
  const outcome = command(databases.main, "drop-legacy");
  drops += 1;
  const progress = main.getCollection("application_migrations")
    .findOne({ _kind: "domain_collection_cutover" }).payload.dropIndex;
  assert(progress === drops, "legacy deletion advanced by more than one operation");
  if (outcome.complete) break;
}
assert(drops > 0, "legacy deletion performed no allowlisted steps");
const dropRevision = main.getCollection("application_migrations")
  .findOne({ _kind: "domain_collection_cutover" }).payload.revision;
assert(command(databases.main, "drop-legacy").complete,
  "completed legacy deletion retry was not idempotent");
assert(main.getCollection("application_migrations")
  .findOne({ _kind: "domain_collection_cutover" }).payload.revision === dropRevision,
"completed legacy deletion retry mutated the ledger");
const names = main.getCollectionInfos().map((info) => info.name)
  .filter((name) => !name.startsWith("system.")).sort();
assert(JSON.stringify(names) === JSON.stringify([...manifest.targets].sort()),
  "final collection catalog is not exact");
const indexCount = manifest.targets.reduce((sum, target) =>
  sum + main.getCollection(target).getIndexes().length, 0);
assert(indexCount === 126, "final index catalog is not exact");
const ledger = main.getCollection("application_migrations")
  .findOne({ _kind: "domain_collection_cutover" });
assert(ledger.payload.state === "TARGET_ACTIVE" && ledger.payload.completed === true
  && ledger.payload.legacyDropped === true && ledger.payload.manifestDigest === digest,
"final cutover ledger is not startup-safe");
expectFailure(() => command(databases.main, "reverse-next"), "post-deletion reversal");

seed(databases.faultMatrix);
command(databases.faultMatrix, "preview");
const stageOperationCount = manifest.kinds.length + manifest.targets.length;
for (let index = 0; index < stageOperationCount; index++) {
  interruptEveryBoundary(databases.faultMatrix, "stage", "stageIndex", index + 1);
}
command(databases.faultMatrix, "verify-stage");
const publicationCount = migration.buildPublicationOperations(
  manifest, currentLedger(databases.faultMatrix).payload.presentSources).length;
for (let index = 0; index < publicationCount; index++) {
  interruptEveryBoundary(databases.faultMatrix, "publish-next", "publishIndex", index + 1);
}
for (let index = publicationCount; index > 0; index--) {
  interruptEveryBoundary(databases.faultMatrix, "reverse-next", "publishIndex", index - 1);
}
command(databases.faultMatrix, "verify-stage");
publishAll(databases.faultMatrix);
command(databases.faultMatrix, "verify-live");
const faultDrops = drops;
for (let index = 0; index < faultDrops; index++) {
  interruptEveryBoundary(databases.faultMatrix, "drop-legacy", "dropIndex", index + 1);
}
assert(currentLedger(databases.faultMatrix).payload.legacyDropped === true,
  "fault matrix did not complete exact legacy deletion");

seed(databases.recoveryInit);
command(databases.recoveryInit, "preview");
db.getSiblingDB(databases.recoveryInit).createCollection("__domain_stage__application_migrations");
recoverPrepublicationScenario(databases.recoveryInit);

seed(databases.recoveryPartialStage);
command(databases.recoveryPartialStage, "preview");
command(databases.recoveryPartialStage, "stage");
recoverPrepublicationScenario(databases.recoveryPartialStage);

seed(databases.recoveryStaged);
command(databases.recoveryStaged, "preview");
stageAll(databases.recoveryStaged);
recoverPrepublicationScenario(databases.recoveryStaged);

seed(databases.recoveryPublishing);
command(databases.recoveryPublishing, "preview");
stageAll(databases.recoveryPublishing);
command(databases.recoveryPublishing, "verify-stage");
command(databases.recoveryPublishing, "publish-next");
recoverPrepublicationScenario(databases.recoveryPublishing);

print(JSON.stringify({ complete: true, databases: Object.keys(databases).length,
  kinds: 52, indexes: 126, collections: names.length, drops,
  faultBoundaries: (stageOperationCount + publicationCount * 2 + faultDrops) * 3 }));
