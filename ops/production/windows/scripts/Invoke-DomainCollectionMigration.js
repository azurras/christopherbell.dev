"use strict";

const ManifestApi = typeof globalThis !== "undefined" && globalThis.DomainCollectionManifest
  ? globalThis.DomainCollectionManifest
  : require("./DomainCollectionManifest.js");

const ACTIONS = Object.freeze([
  "preview", "stage", "verify-stage", "publish-next", "verify-live",
  "drop-legacy", "reverse-next", "restore-verify"
]);
const DATABASE = /^(christopherbell|cbell_candidate_[0-9a-f]{12}_[0-9a-f]{24})$/;
const DIGEST_PATTERN = /^[0-9a-f]{64}$/;
const OWNER = /^[0-9a-f]{32}$/;
const RELEASE = /^[0-9a-f]{40}$/;
const LEDGER_ID = "domain-collection-cutover";
const LEDGER_KIND = "domain_collection_cutover";
const STAGE_PREFIX = "__domain_stage__";
const LEGACY_PREFIX = "__domain_legacy__";

function fail(message) {
  throw new Error(message);
}

function parseCommand(args) {
  if (!Array.isArray(args) || args.length !== 5) fail("Mongo migration arguments are invalid.");
  const [database, action, manifestDigest, ownerToken, release] = args;
  if (typeof database !== "string" || !DATABASE.test(database)) {
    fail("Mongo migration database is invalid.");
  }
  if (typeof action !== "string" || !ACTIONS.includes(action)) {
    fail("Mongo migration action is invalid.");
  }
  if (typeof manifestDigest !== "string" || !DIGEST_PATTERN.test(manifestDigest)
      || manifestDigest !== ManifestApi.DIGEST) {
    fail("Mongo migration manifest digest is invalid.");
  }
  if (typeof ownerToken !== "string" || !OWNER.test(ownerToken)) {
    fail("Mongo migration owner is invalid.");
  }
  if (typeof release !== "string" || !RELEASE.test(release)) {
    fail("Mongo migration release is invalid.");
  }
  return Object.freeze({ database, action, manifestDigest, ownerToken, release });
}

function redactedFailure(args) {
  const values = Array.isArray(args) ? args : [];
  const database = typeof values[0] === "string" && DATABASE.test(values[0]) ? values[0] : null;
  const action = typeof values[1] === "string" && ACTIONS.includes(values[1]) ? values[1] : null;
  const manifestDigest = typeof values[2] === "string" && values[2] === ManifestApi.DIGEST
    ? values[2] : null;
  return Object.freeze({
    complete: false,
    database,
    action,
    state: "FAILED",
    manifestDigest,
    kinds: Object.freeze([]),
    indexes: Object.freeze([]),
    nextOperation: null,
    category: "DOMAIN_COLLECTION_MIGRATION_FAILED"
  });
}

function cryptoModule() {
  if (typeof require !== "function") fail("SHA-256 is unavailable.");
  try {
    return require("node:crypto");
  } catch (ignored) {
    return require("crypto");
  }
}

function sha256(value) {
  return cryptoModule().createHash("sha256").update(value, "utf8").digest("hex");
}

function canonicalValue(value) {
  if (Array.isArray(value)) return value.map(canonicalValue);
  if (value && typeof value === "object") {
    const result = {};
    for (const key of Object.keys(value).sort()) result[key] = canonicalValue(value[key]);
    return result;
  }
  return value;
}

function canonicalExtendedJson(value) {
  let portable = value;
  if (typeof EJSON !== "undefined" && EJSON && typeof EJSON.stringify === "function") {
    portable = JSON.parse(EJSON.stringify(value, { relaxed: false }));
  }
  return JSON.stringify(canonicalValue(portable));
}

function canonicalChecksum(documents) {
  const rows = documents.map(canonicalExtendedJson).sort();
  return sha256(rows.join("\n") + (rows.length === 0 ? "" : "\n"));
}

function sourceNames(manifest) {
  return [...new Set(manifest.kinds.filter((kind) => kind.source).map((kind) => kind.source))];
}

function orderedTargets(manifest) {
  return manifest.targets.filter((target) => target !== "application_migrations")
    .concat("application_migrations");
}

function buildPublicationOperations(manifest, presentSources) {
  const present = new Set(presentSources || sourceNames(manifest));
  const operations = [];
  for (const target of orderedTargets(manifest)) {
    if (present.has(target)) {
      operations.push(Object.freeze({ kind: "rename", from: target, to: LEGACY_PREFIX + target }));
    }
    operations.push(Object.freeze({ kind: "rename", from: STAGE_PREFIX + target, to: target }));
  }
  return Object.freeze(operations);
}

function reversePublicationOperations(operations) {
  return operations.toReversed().map((operation) => Object.freeze({
    kind: "rename", from: operation.to, to: operation.from
  }));
}

function collectionExists(database, name) {
  return database.getCollectionInfos({ name }).length === 1;
}

function applicationCollections(database) {
  return database.getCollectionInfos()
    .map((info) => info.name)
    .filter((name) => !name.startsWith("system."))
    .sort();
}

function assertInitialInventory(database, manifest) {
  const approved = new Set(sourceNames(manifest).concat(manifest.dropOnly));
  for (const name of applicationCollections(database)) {
    if (name.startsWith(STAGE_PREFIX) || name.startsWith(LEGACY_PREFIX) || !approved.has(name)) {
      fail("Mongo migration inventory contains an unexpected collection.");
    }
  }
}

function assertSupportedId(value) {
  if (value === null || value === undefined || Array.isArray(value)) {
    fail("Mongo source identity is invalid.");
  }
  const type = typeof value;
  if (type === "string") return;
  if (type === "number" && Number.isSafeInteger(value)) return;
  if (type !== "object") fail("Mongo source identity type is invalid.");
  const name = value && value.constructor ? value.constructor.name : "";
  if (!["ObjectId", "Long", "Int32", "Decimal128", "Binary", "UUID"].includes(name)) {
    fail("Mongo source identity type is invalid.");
  }
}

function sourceDocumentMatches(kind, document) {
  if (kind.sourceId === null) return true;
  return canonicalExtendedJson(document._id) === canonicalExtendedJson(kind.sourceId);
}

function sourceDocuments(database, kind) {
  if (!kind.source || !collectionExists(database, kind.source)) return [];
  return database.getCollection(kind.source).find({}).toArray()
    .filter((document) => sourceDocumentMatches(kind, document));
}

function validateSharedVehicleSource(database, manifest) {
  if (!collectionExists(database, "vehicle_import_state")) return;
  const kinds = manifest.kinds.filter((kind) => kind.source === "vehicle_import_state");
  for (const document of database.getCollection("vehicle_import_state").find({}).toArray()) {
    assertSupportedId(document._id);
    if (kinds.filter((kind) => sourceDocumentMatches(kind, document)).length !== 1) {
      fail("Mongo shared source identity is not approved.");
    }
  }
}

function assertV014Authority(database) {
  if (!collectionExists(database, "application_migrations")) {
    fail("Mongo V014 authority is absent.");
  }
  const migration = database.getCollection("application_migrations").findOne({
    _id: "014-consolidate-music-runtime-state", status: "APPLIED"
  });
  if (!migration) fail("Mongo V014 authority is absent.");
  if (!collectionExists(database, "music_runtime_state")) {
    fail("Mongo V014 authoritative source is absent.");
  }
}

function legacyShape(document) {
  assertSupportedId(document._id);
  const copy = {};
  for (const key of Object.keys(document)) copy[key] = document[key];
  return copy;
}

function envelope(kind, legacyDocument) {
  const source = legacyShape(legacyDocument);
  const legacyId = source._id;
  delete source._id;
  return {
    _id: { kind: kind.kind, legacyId },
    _kind: kind.kind,
    schemaVersion: kind.schemaVersion,
    payload: source
  };
}

function envelopeToLegacy(document, kind) {
  const keys = Object.keys(document);
  const id = document._id;
  if (canonicalExtendedJson(keys) !== canonicalExtendedJson(["_id", "_kind", "schemaVersion", "payload"])
      || !id || canonicalExtendedJson(Object.keys(id)) !== canonicalExtendedJson(["kind", "legacyId"])
      || id.kind !== kind.kind || document._kind !== kind.kind
      || document.schemaVersion !== kind.schemaVersion || !document.payload
      || typeof document.payload !== "object" || Array.isArray(document.payload)
      || Object.prototype.hasOwnProperty.call(document.payload, "_id")) {
    fail("Mongo staged envelope is malformed.");
  }
  const result = { _id: id.legacyId };
  for (const key of Object.keys(document.payload)) result[key] = document.payload[key];
  return result;
}

function kindMetricsFromLegacy(database, manifest) {
  return manifest.kinds.map((kind) => {
    const documents = kind.source ? sourceDocuments(database, kind).map(legacyShape) : [];
    return Object.freeze({ kind: kind.kind, count: documents.length, checksum: canonicalChecksum(documents) });
  });
}

function kindMetricsFromTarget(database, manifest, staged) {
  return manifest.kinds.map((kind) => {
    if (kind.kind === LEDGER_KIND) {
      return Object.freeze({ kind: kind.kind, count: 1, checksum: "<ledger>" });
    }
    const name = staged ? STAGE_PREFIX + kind.target : kind.target;
    const documents = collectionExists(database, name)
      ? database.getCollection(name).find({ _kind: kind.kind }).toArray()
          .map((document) => envelopeToLegacy(document, kind))
      : [];
    return Object.freeze({ kind: kind.kind, count: documents.length, checksum: canonicalChecksum(documents) });
  });
}

function kindMetricsFromAvailableTarget(database, manifest) {
  return manifest.kinds.map((kind) => {
    if (kind.kind === LEDGER_KIND) {
      return Object.freeze({ kind: kind.kind, count: 1, checksum: "<ledger>" });
    }
    const staged = STAGE_PREFIX + kind.target;
    const name = collectionExists(database, staged) ? staged : kind.target;
    const documents = collectionExists(database, name)
      ? database.getCollection(name).find({ _kind: kind.kind }).toArray()
          .map((document) => envelopeToLegacy(document, kind))
      : [];
    return Object.freeze({
      kind: kind.kind,
      count: documents.length,
      checksum: canonicalChecksum(documents)
    });
  });
}

function indexKeysDocument(index) {
  const keys = {};
  for (const [path, direction] of index.keys) keys[path] = direction;
  return keys;
}

function indexOptions(index) {
  const options = { name: index.name };
  if (index.unique) options.unique = true;
  if (index.sparse) options.sparse = true;
  if (Object.keys(index.partialFilterExpression).length !== 0) {
    options.partialFilterExpression = index.partialFilterExpression;
  }
  if (index.expireAfterSeconds !== null) options.expireAfterSeconds = index.expireAfterSeconds;
  if (index.collation !== null) options.collation = index.collation;
  return options;
}

function expectedIndexSemantics(index) {
  return {
    name: index.name,
    key: indexKeysDocument(index),
    unique: index.name === "_id_" || index.unique,
    sparse: index.sparse,
    partialFilterExpression: index.partialFilterExpression,
    expireAfterSeconds: index.expireAfterSeconds,
    collation: index.collation
  };
}

function actualIndexSemantics(index) {
  return {
    name: index.name,
    key: index.key,
    unique: index.name === "_id_" || index.unique === true,
    sparse: index.sparse === true,
    partialFilterExpression: index.partialFilterExpression || {},
    expireAfterSeconds: index.expireAfterSeconds === undefined ? null : Number(index.expireAfterSeconds),
    collation: index.collation || null
  };
}

function indexMetrics(database, manifest, staged) {
  return manifest.targets.map((target) => {
    const name = staged ? STAGE_PREFIX + target : target;
    if (!collectionExists(database, name)) {
      return Object.freeze({ target, count: 0, digest: canonicalChecksum([]) });
    }
    const actual = database.getCollection(name).getIndexes().map(actualIndexSemantics)
      .sort((left, right) => left.name.localeCompare(right.name));
    return Object.freeze({ target, count: actual.length, digest: sha256(canonicalExtendedJson(actual)) });
  });
}

function indexMetricsFromAvailableTarget(database, manifest) {
  return manifest.targets.map((target) => {
    const staged = STAGE_PREFIX + target;
    const name = collectionExists(database, staged) ? staged : target;
    if (!collectionExists(database, name)) {
      return Object.freeze({ target, count: 0, digest: canonicalChecksum([]) });
    }
    const actual = database.getCollection(name).getIndexes().map(actualIndexSemantics)
        .sort((left, right) => left.name.localeCompare(right.name));
    return Object.freeze({
      target,
      count: actual.length,
      digest: sha256(canonicalExtendedJson(actual))
    });
  });
}

function requireExactIndexes(database, manifest, staged) {
  for (const target of manifest.targets) {
    const name = staged ? STAGE_PREFIX + target : target;
    if (!collectionExists(database, name)) fail("Mongo target collection is absent.");
    const expected = manifest.indexes.filter((index) => index.target === target)
      .map(expectedIndexSemantics).sort((left, right) => left.name.localeCompare(right.name));
    const actual = database.getCollection(name).getIndexes().map(actualIndexSemantics)
      .sort((left, right) => left.name.localeCompare(right.name));
    if (canonicalExtendedJson(actual) !== canonicalExtendedJson(expected)) {
      fail("Mongo target indexes do not match the manifest.");
    }
  }
}

function result(command, state, kinds, indexes, nextOperation, complete) {
  return Object.freeze({
    complete,
    database: command.database,
    action: command.action,
    state,
    manifestDigest: command.manifestDigest,
    kinds,
    indexes,
    nextOperation
  });
}

function ledgerId() {
  return { kind: LEDGER_KIND, legacyId: LEDGER_ID };
}

function ledgerNames() {
  return [STAGE_PREFIX + "application_migrations", "application_migrations"];
}

function findLedger(database) {
  const found = ledgerNames().filter((name) => collectionExists(database, name))
    .map((name) => ({ name, value: database.getCollection(name).findOne({
      _id: ledgerId(), _kind: LEDGER_KIND
    }) })).filter((entry) => entry.value);
  if (found.length !== 1) fail("Mongo cutover ledger is absent or ambiguous.");
  const payload = found[0].value.payload;
  if (!payload || payload.manifestDigest !== ManifestApi.DIGEST
      || !OWNER.test(payload.ownerToken) || !RELEASE.test(payload.release)
      || !Number.isSafeInteger(payload.revision) || payload.revision < 1) {
    fail("Mongo cutover ledger is malformed.");
  }
  return { collection: found[0].name, document: found[0].value, payload };
}

function requireLedger(database, command) {
  const ledger = findLedger(database);
  if (ledger.payload.ownerToken !== command.ownerToken
      || ledger.payload.release !== command.release
      || ledger.payload.manifestDigest !== command.manifestDigest) {
    fail("Mongo cutover ledger ownership does not match.");
  }
  return ledger;
}

function updateLedger(database, ledger, fields) {
  const set = {};
  for (const [key, value] of Object.entries(fields)) set["payload." + key] = value;
  const write = database.getCollection(ledger.collection).updateOne({
    _id: ledgerId(),
    _kind: LEDGER_KIND,
    "payload.ownerToken": ledger.payload.ownerToken,
    "payload.revision": ledger.payload.revision
  }, { $set: set, $inc: { "payload.revision": 1 } });
  if (write.matchedCount !== 1 || write.modifiedCount !== 1) {
    fail("Mongo cutover ledger ownership was lost.");
  }
}

function preview(database, command, manifest) {
  assertInitialInventory(database, manifest);
  assertV014Authority(database);
  validateSharedVehicleSource(database, manifest);
  const metrics = kindMetricsFromLegacy(database, manifest);
  return result(command, "PREVIEWED", metrics, indexMetrics(database, manifest, false),
    "stage", true);
}

function initializeLedger(database, command, manifest) {
  assertInitialInventory(database, manifest);
  assertV014Authority(database);
  validateSharedVehicleSource(database, manifest);
  const name = STAGE_PREFIX + "application_migrations";
  if (collectionExists(database, name)) fail("Mongo staging residue is present.");
  database.createCollection(name);
  const presentSources = sourceNames(manifest).filter((source) => collectionExists(database, source));
  const drops = [...new Set(presentSources.filter((source) => !manifest.targets.includes(source))
    .concat(manifest.dropOnly.filter((source) => collectionExists(database, source)))
    .concat(presentSources.filter((source) => manifest.targets.includes(source))
      .map((source) => LEGACY_PREFIX + source)))].sort();
  const payload = {
    state: "STAGING",
    manifestDigest: command.manifestDigest,
    ownerToken: command.ownerToken,
    release: command.release,
    revision: 1,
    stageIndex: 0,
    publishIndex: 0,
    dropIndex: 0,
    completed: false,
    legacyDropped: false,
    presentSources,
    expectedKindMetrics: kindMetricsFromLegacy(database, manifest),
    publicationOperations: buildPublicationOperations(manifest, presentSources),
    dropCollections: drops
  };
  database.getCollection(name).insertOne({
    _id: ledgerId(), _kind: LEDGER_KIND, schemaVersion: 1, payload
  });
  return findLedger(database);
}

function stageKind(database, manifest, kind) {
  const target = STAGE_PREFIX + kind.target;
  if (!collectionExists(database, target)) database.createCollection(target);
  const collection = database.getCollection(target);
  for (const source of sourceDocuments(database, kind)) {
    const staged = envelope(kind, source);
    const existing = collection.findOne({ _id: staged._id });
    if (existing && canonicalExtendedJson(existing) !== canonicalExtendedJson(staged)) {
      fail("Mongo staging identity collision was detected.");
    }
    if (!existing) collection.insertOne(staged);
  }
}

function stageIndexes(database, manifest, target) {
  const name = STAGE_PREFIX + target;
  if (!collectionExists(database, name)) database.createCollection(name);
  for (const index of manifest.indexes.filter((candidate) =>
    candidate.target === target && candidate.name !== "_id_")) {
    database.getCollection(name).createIndex(indexKeysDocument(index), indexOptions(index));
  }
}

function stage(database, command, manifest) {
  let ledger;
  try {
    ledger = requireLedger(database, command);
  } catch (failure) {
    if (!String(failure.message).includes("absent")) throw failure;
    ledger = initializeLedger(database, command, manifest);
  }
  if (ledger.payload.state === "STAGED") {
    return result(command, "STAGED", kindMetricsFromTarget(database, manifest, true),
      indexMetrics(database, manifest, true), "verify-stage", true);
  }
  if (ledger.payload.state !== "STAGING") fail("Mongo cutover ledger state is invalid for staging.");
  const operationCount = manifest.kinds.length + manifest.targets.length;
  const index = ledger.payload.stageIndex;
  if (!Number.isSafeInteger(index) || index < 0 || index > operationCount) {
    fail("Mongo staging progress is invalid.");
  }
  if (index < manifest.kinds.length) {
    stageKind(database, manifest, manifest.kinds[index]);
  } else if (index < operationCount) {
    stageIndexes(database, manifest, manifest.targets[index - manifest.kinds.length]);
  }
  const next = Math.min(index + 1, operationCount);
  updateLedger(database, findLedger(database), {
    stageIndex: next,
    state: next === operationCount ? "STAGED" : "STAGING"
  });
  const current = findLedger(database).payload;
  return result(command, current.state, kindMetricsFromTarget(database, manifest, true),
    indexMetrics(database, manifest, true),
    current.state === "STAGED" ? "verify-stage" : "stage", current.state === "STAGED");
}

function requireEquivalentMetrics(left, right) {
  const byKind = new Map(right.map((entry) => [entry.kind, entry]));
  for (const source of left) {
    if (source.kind === LEDGER_KIND) continue;
    const target = byKind.get(source.kind);
    if (!target || source.count !== target.count || source.checksum !== target.checksum) {
      fail("Mongo kind count or checksum does not match.");
    }
  }
}

function verifyStage(database, command, manifest) {
  const ledger = requireLedger(database, command);
  if (ledger.payload.state === "STAGE_VERIFIED") {
    const staged = kindMetricsFromTarget(database, manifest, true);
    requireEquivalentMetrics(ledger.payload.expectedKindMetrics, staged);
    requireExactIndexes(database, manifest, true);
    return result(command, "STAGE_VERIFIED", staged, indexMetrics(database, manifest, true),
      "publish-next", true);
  }
  if (!["STAGED", "LEGACY_ACTIVE"].includes(ledger.payload.state)) {
    fail("Mongo cutover ledger state is invalid for stage verification.");
  }
  const source = kindMetricsFromLegacy(database, manifest);
  const staged = kindMetricsFromTarget(database, manifest, true);
  requireEquivalentMetrics(source, staged);
  requireExactIndexes(database, manifest, true);
  updateLedger(database, ledger, { state: "STAGE_VERIFIED", expectedKindMetrics: source });
  return result(command, "STAGE_VERIFIED", staged, indexMetrics(database, manifest, true),
    "publish-next", true);
}

function rename(database, operation) {
  const fromExists = collectionExists(database, operation.from);
  const toExists = collectionExists(database, operation.to);
  if (fromExists && !toExists) {
    const outcome = database.getSiblingDB("admin").runCommand({
      renameCollection: database.getName() + "." + operation.from,
      to: database.getName() + "." + operation.to,
      dropTarget: false
    });
    if (!outcome.ok) fail("Mongo collection rename failed.");
    return;
  }
  if (!fromExists && toExists) return;
  fail("Mongo collection rename state is invalid.");
}

function publishNext(database, command, manifest) {
  let ledger = requireLedger(database, command);
  if (ledger.payload.state === "PUBLISHED") {
    return result(command, "PUBLISHED", kindMetricsFromAvailableTarget(database, manifest),
      indexMetricsFromAvailableTarget(database, manifest), "verify-live", true);
  }
  if (!["STAGE_VERIFIED", "PUBLISHING"].includes(ledger.payload.state)) {
    fail("Mongo cutover ledger state is invalid for publication.");
  }
  if (ledger.payload.state === "STAGE_VERIFIED") {
    updateLedger(database, ledger, { state: "PUBLISHING" });
    ledger = requireLedger(database, command);
  }
  const operations = ledger.payload.publicationOperations;
  const index = ledger.payload.publishIndex;
  if (!Array.isArray(operations) || !Number.isSafeInteger(index) || index < 0 || index > operations.length) {
    fail("Mongo publication progress is invalid.");
  }
  if (index < operations.length) rename(database, operations[index]);
  ledger = requireLedger(database, command);
  const next = Math.min(index + 1, operations.length);
  updateLedger(database, ledger, {
    publishIndex: next,
    state: next === operations.length ? "PUBLISHED" : "PUBLISHING"
  });
  const current = requireLedger(database, command).payload;
  return result(command, current.state, kindMetricsFromAvailableTarget(database, manifest),
    indexMetricsFromAvailableTarget(database, manifest),
    current.state === "PUBLISHED" ? "verify-live" : "publish-next",
    current.state === "PUBLISHED");
}

function verifyLive(database, command, manifest) {
  const ledger = requireLedger(database, command);
  if (ledger.payload.state === "TARGET_ACTIVE" && ledger.payload.completed === true) {
    const live = kindMetricsFromTarget(database, manifest, false);
    requireEquivalentMetrics(ledger.payload.expectedKindMetrics, live);
    requireExactIndexes(database, manifest, false);
    return result(command, "TARGET_ACTIVE", live, indexMetrics(database, manifest, false),
      ledger.payload.legacyDropped === true ? null : "drop-legacy", true);
  }
  if (ledger.payload.state !== "PUBLISHED") fail("Mongo cutover ledger state is invalid for live verification.");
  const source = ledger.payload.expectedKindMetrics;
  if (!Array.isArray(source) || source.length !== manifest.kinds.length) {
    fail("Mongo cutover ledger verification evidence is malformed.");
  }
  const live = kindMetricsFromTarget(database, manifest, false);
  requireEquivalentMetrics(source, live);
  requireExactIndexes(database, manifest, false);
  updateLedger(database, ledger, { state: "TARGET_ACTIVE", completed: true });
  return result(command, "TARGET_ACTIVE", live, indexMetrics(database, manifest, false),
    "drop-legacy", true);
}

function dropLegacy(database, command, manifest) {
  let ledger = requireLedger(database, command);
  if (ledger.payload.state !== "TARGET_ACTIVE" || ledger.payload.completed !== true) {
    fail("Mongo cutover ledger state is invalid for legacy deletion.");
  }
  const drops = ledger.payload.dropCollections;
  const index = ledger.payload.dropIndex;
  if (!Array.isArray(drops) || !Number.isSafeInteger(index) || index < 0 || index > drops.length) {
    fail("Mongo legacy deletion progress is invalid.");
  }
  if (ledger.payload.legacyDropped === true && index === drops.length) {
    return result(command, "TARGET_ACTIVE", kindMetricsFromTarget(database, manifest, false),
      indexMetrics(database, manifest, false), null, true);
  }
  if (index < drops.length && collectionExists(database, drops[index])) {
    database.getCollection(drops[index]).drop();
  }
  ledger = requireLedger(database, command);
  const next = Math.min(index + 1, drops.length);
  updateLedger(database, ledger, { dropIndex: next, legacyDropped: next === drops.length });
  const current = requireLedger(database, command).payload;
  return result(command, "TARGET_ACTIVE", kindMetricsFromTarget(database, manifest, false),
    indexMetrics(database, manifest, false),
    current.legacyDropped ? null : "drop-legacy", current.legacyDropped === true);
}

function reverseNext(database, command, manifest) {
  let ledger = requireLedger(database, command);
  if (ledger.payload.dropIndex !== 0 || ledger.payload.legacyDropped === true) {
    fail("Mongo publication cannot be reversed after legacy deletion.");
  }
  if (ledger.payload.state === "LEGACY_ACTIVE") {
    return result(command, "LEGACY_ACTIVE", kindMetricsFromAvailableTarget(database, manifest),
      indexMetricsFromAvailableTarget(database, manifest), null, true);
  }
  if (!["PUBLISHING", "PUBLISHED", "TARGET_ACTIVE", "REVERSING"].includes(ledger.payload.state)) {
    fail("Mongo cutover ledger state is invalid for reversal.");
  }
  if (ledger.payload.state !== "REVERSING") {
    updateLedger(database, ledger, { state: "REVERSING", completed: false });
    ledger = requireLedger(database, command);
  }
  const index = ledger.payload.publishIndex;
  if (!Number.isSafeInteger(index) || index < 0) fail("Mongo reversal progress is invalid.");
  if (index > 0) {
    const forward = ledger.payload.publicationOperations[index - 1];
    rename(database, { from: forward.to, to: forward.from });
  }
  ledger = requireLedger(database, command);
  const next = Math.max(0, index - 1);
  updateLedger(database, ledger, {
    publishIndex: next,
    state: next === 0 ? "LEGACY_ACTIVE" : "REVERSING"
  });
  return result(command, next === 0 ? "LEGACY_ACTIVE" : "REVERSING",
    kindMetricsFromAvailableTarget(database, manifest),
    indexMetricsFromAvailableTarget(database, manifest),
    next === 0 ? null : "reverse-next", next === 0);
}

function restoreVerify(database, command, manifest) {
  assertInitialInventory(database, manifest);
  assertV014Authority(database);
  validateSharedVehicleSource(database, manifest);
  return result(command, "LEGACY_RESTORE_VERIFIED", kindMetricsFromLegacy(database, manifest),
    indexMetrics(database, manifest, false), null, true);
}

function execute(rootDatabase, args) {
  const command = parseCommand(args);
  const manifest = ManifestApi.requireDigest(command.manifestDigest);
  const database = rootDatabase.getSiblingDB(command.database);
  const handlers = Object.freeze({
    preview, stage,
    "verify-stage": verifyStage,
    "publish-next": publishNext,
    "verify-live": verifyLive,
    "drop-legacy": dropLegacy,
    "reverse-next": reverseNext,
    "restore-verify": restoreVerify
  });
  return handlers[command.action](database, command, manifest);
}

const exported = Object.freeze({
  parseCommand,
  redactedFailure,
  canonicalExtendedJson,
  canonicalChecksum,
  buildPublicationOperations,
  reversePublicationOperations,
  execute
});

if (typeof module !== "undefined" && module.exports) module.exports = exported;
if (typeof globalThis !== "undefined") globalThis.DomainCollectionMigration = exported;

if (typeof db !== "undefined" && typeof globalThis !== "undefined"
    && Array.isArray(globalThis.DOMAIN_COLLECTION_ARGS)) {
  try {
    print(JSON.stringify(execute(db, globalThis.DOMAIN_COLLECTION_ARGS)));
  } catch (failure) {
    print(JSON.stringify(redactedFailure(globalThis.DOMAIN_COLLECTION_ARGS)));
    if (typeof quit === "function") quit(1);
    throw new Error("Domain collection migration failed.");
  }
}
