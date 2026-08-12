"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const manifestModule = require("../scripts/DomainCollectionManifest.js");
const migration = require("../scripts/Invoke-DomainCollectionMigration.js");
const backupIdentity = "b".repeat(64);
const noExpectedEvidence = "0".repeat(64);

test("manifest freezes all targets kinds sources indexes and V014 drop-only artifacts", () => {
  const manifest = manifestModule.requireDigest(
    "576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24");

  assert.equal(manifest.targets.length, 14);
  assert.equal(manifest.kinds.length, 52);
  assert.equal(manifest.kinds.filter((kind) => kind.source !== null).length, 51);
  assert.equal(new Set(manifest.kinds.filter((kind) => kind.source).map((kind) => kind.source)).size, 50);
  assert.equal(manifest.indexes.length, 126);
  assert.deepEqual(manifest.dropOnly, ["music_queue_state", "music_radio_state"]);
  assert.equal(manifest.kinds.find((kind) => kind.kind === "music_runtime_state").source,
    "music_runtime_state");
});

test("command parser rejects unsafe database action digest owner and release inputs", () => {
  const valid = ["cbell_candidate_123456789abc_1234567890abcdef12345678", "preview",
    "576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24",
    "0123456789abcdef0123456789abcdef", "a".repeat(40), backupIdentity,
    noExpectedEvidence];
  assert.equal(migration.parseCommand(valid).action, "preview");
  assert.equal(migration.parseCommand(valid).backupIdentity, backupIdentity);
  for (const action of ["recover-prepublication", "prepare-restore"]) {
    const args = valid.slice();
    args[1] = action;
    args[6] = "1".repeat(64);
    assert.equal(migration.parseCommand(args).action, action);
  }

  for (const replacement of [
    [0, "admin"], [1, "eval"], [2, "0".repeat(64)], [3, "owner value"],
    [4, "A".repeat(40)], [5, "backup"], [6, "evidence"]
  ]) {
    const args = valid.slice();
    args[replacement[0]] = replacement[1];
    assert.throws(() => migration.parseCommand(args), /invalid/i);
  }
});

test("failure results retain the action contract without echoing unsafe arguments", () => {
  const failure = migration.redactedFailure([
    "admin", "eval", "secret-digest", "secret-owner", "secret-release", "secret-backup"
  ]);

  assert.deepEqual(failure, {
    complete: false,
    database: null,
    action: null,
    state: "FAILED",
    manifestDigest: null,
    backupIdentity: null,
    expectedEvidenceDigest: null,
    evidenceDigest: null,
    evidence: null,
    kinds: [],
    indexes: [],
    nextOperation: null,
    category: "DOMAIN_COLLECTION_MIGRATION_FAILED"
  });
  assert.equal(JSON.stringify(failure).includes("secret"), false);
});

test("protected evidence is bound to manifest release backup and exact content", () => {
  const evidence = {
    version: 1,
    manifestDigest: manifestModule.DIGEST,
    release: "a".repeat(40),
    backupIdentity,
    presentSources: ["accounts", "application_migrations", "music_runtime_state"],
    kinds: manifestModule.MANIFEST.kinds.map((kind) => ({
      kind: kind.kind, count: 0, checksum: "0".repeat(64)
    })),
    collections: [],
    v014: {
      id: "014-consolidate-music-runtime-state",
      checksum: "11a69bdd4556cfc38060ccdda5075fb9d6bc36f1cc414edd7b26cd61a74b5cbb",
      queueChecksum: "0".repeat(64),
      radioChecksum: "1".repeat(64),
      targetChecksum: "2".repeat(64)
    }
  };
  const digest = migration.evidenceDigest(evidence);
  const command = migration.parseCommand([
    "cbell_candidate_123456789abc_1234567890abcdef12345678", "stage",
    manifestModule.DIGEST, "0123456789abcdef0123456789abcdef", "a".repeat(40),
    backupIdentity, digest
  ]);

  assert.equal(migration.requireProtectedEvidence(command, evidence), evidence);
  assert.throws(() => migration.requireProtectedEvidence(
    command, { ...evidence, backupIdentity: "c".repeat(64) }), /evidence/i);
  assert.throws(() => migration.requireProtectedEvidence(
    command, { ...evidence, presentSources: ["accounts"] }), /evidence/i);
  const replacement = { ...evidence, collections: [{
    name: "accounts", count: 0, checksum: "3".repeat(64), indexDigest: "4".repeat(64)
  }] };
  assert.notEqual(migration.evidenceDigest(replacement), digest);
  assert.throws(() => migration.requireProtectedEvidence(command, replacement), /evidence/i,
    "replacement evidence must not authenticate against its recomputed digest");
});

test("canonical Extended JSON distinguishes BSON types and normalizes document keys", () => {
  assert.notEqual(
    migration.canonicalExtendedJson({ value: { $numberLong: "7" } }),
    migration.canonicalExtendedJson({ value: 7 }));
  assert.equal(
    migration.canonicalExtendedJson({ z: 1, a: { y: 2, x: 3 } }),
    migration.canonicalExtendedJson({ a: { x: 3, y: 2 }, z: 1 }));
});

test("index semantics distinguish hidden state and reject unmodeled behavior", () => {
  const visible = migration.canonicalIndexSemantics({ v: 2, name: "lookup", key: { value: 1 } });
  const hidden = migration.canonicalIndexSemantics({
    v: 2, name: "lookup", key: { value: 1 }, hidden: true
  });

  assert.equal(visible.hidden, false);
  assert.equal(hidden.hidden, true);
  assert.notEqual(migration.canonicalExtendedJson(visible),
    migration.canonicalExtendedJson(hidden));
  assert.throws(() => migration.canonicalIndexSemantics({
    v: 2, name: "lookup", key: { value: 1 }, storageEngine: { wiredTiger: {} }
  }), /index/i);
  assert.throws(() => migration.canonicalIndexSemantics({
    v: 2, name: "wildcard", key: { "$**": 1 }, wildcardProjection: { secret: 0 }
  }), /index/i);
});

test("publication plan performs one rename at a time and remains exactly reversible", () => {
  const manifest = manifestModule.requireDigest(manifestModule.DIGEST);
  const operations = migration.buildPublicationOperations(manifest);
  assert.equal(operations.filter((operation) => operation.kind === "rename").length,
    operations.length);
  assert.equal(new Set(operations.map((operation) => `${operation.from}->${operation.to}`)).size,
    operations.length);
  assert.deepEqual(migration.reversePublicationOperations(operations),
    operations.toReversed().map((operation) => ({
      kind: "rename", from: operation.to, to: operation.from
    })));
});

test("recovery allowlists only staged and manifest-owned restore namespaces", () => {
  const manifest = manifestModule.requireDigest(manifestModule.DIGEST);
  const expectedRestore = [...new Set(manifest.targets
    .concat(manifest.kinds.filter((kind) => kind.source).map((kind) => kind.source))
    .concat(manifest.dropOnly)
    .concat(manifest.targets.map((target) => `__domain_legacy__${target}`)))].sort();

  assert.deepEqual(migration.buildRestoreNamespaces(manifest), expectedRestore);
  assert.deepEqual(migration.buildStageNamespaces(manifest),
    manifest.targets.map((target) => `__domain_stage__${target}`).sort());
  assert.equal(migration.buildRestoreNamespaces(manifest).some(
    (name) => name.startsWith("__domain_legacy__")), true);
});

test("Java and JavaScript enforce the shared exact ledger field contract", () => {
  const contractPath = path.resolve(__dirname,
    "../../../../website/src/test/resources/domain-collection-ledger-contract.txt");
  const contract = new Map(fs.readFileSync(contractPath, "utf8").trim().split(/\r?\n/)
    .map((line) => {
      const [name, fields] = line.split("|");
      return [name, fields.split(",")];
    }));
  const manifest = manifestModule.MANIFEST;
  const presentSources = [...new Set(manifest.kinds.filter((kind) => kind.source)
    .map((kind) => kind.source))].sort();
  const metrics = manifest.kinds.map((kind) => ({
    kind: kind.kind, count: 0, checksum: "0".repeat(64)
  }));
  const evidence = { presentSources, kinds: metrics };
  const payloadValues = {
    state: "TARGET_ACTIVE", manifestDigest: manifest.digest,
    ownerToken: "0".repeat(32), release: "1".repeat(40), backupIdentity: "2".repeat(64),
    evidenceDigest: "3".repeat(64), revision: 7, stageIndex: 66, publishIndex: 19,
    dropIndex: 0, completed: true, legacyDropped: false, intent: null,
    presentSources, expectedKindMetrics: metrics
  };
  const payload = Object.fromEntries(contract.get("payload")
    .map((field) => [field, payloadValues[field]]));
  const id = Object.fromEntries(contract.get("id").map((field) => [field,
    field === "kind" ? "domain_collection_cutover" : "domain-collection-cutover"]));
  const values = { _id: id, _kind: "domain_collection_cutover", schemaVersion: 1, payload };
  const ledger = Object.fromEntries(contract.get("envelope").map((field) => [field, values[field]]));
  const command = migration.parseCommand([
    "cbell_candidate_123456789abc_1234567890abcdef12345678", "verify-live",
    manifest.digest, payload.ownerToken, payload.release, payload.backupIdentity,
    payload.evidenceDigest
  ]);

  assert.equal(migration.requireLedgerDocument(ledger, command, evidence), ledger);
  assert.throws(() => migration.requireLedgerDocument(
    { ...ledger, unexpected: true }, command, evidence), /ledger/i);
  assert.throws(() => migration.requireLedgerDocument({ ...ledger,
    payload: { ...payload, dropIndex: 0n } }, command, evidence), /ledger/i);
});
