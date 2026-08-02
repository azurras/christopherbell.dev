import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const JS_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../main/resources/static/js',
);
const MAX_INITIAL_BYTES = 86_434;
const STATIC_IMPORT = /(?:^|\n)\s*import\s+(?:['"](\.\.?\/[^'"]+)['"]|[\s\S]*?\bfrom\s+['"](\.\.?\/[^'"]+)['"]);?/g;

function assertInsideRoot(file, root) {
  const relative = path.relative(root, file);
  assert.ok(relative && !relative.startsWith(`..${path.sep}`) && relative !== '..'
    && !path.isAbsolute(relative), `Import escaped static/js: ${file}`);
}

async function initialGraph(entry, root = JS_ROOT, visited = new Set()) {
  const resolved = path.resolve(entry);
  assertInsideRoot(resolved, root);
  if (visited.has(resolved)) return visited;

  visited.add(resolved);
  const source = await readFile(resolved, 'utf8');
  for (const match of source.matchAll(STATIC_IMPORT)) {
    const specifier = match[1] ?? match[2];
    const child = path.resolve(path.dirname(resolved), specifier);
    await initialGraph(path.extname(child) ? child : `${child}.js`, root, visited);
  }
  return visited;
}

test('static graph counts cycles once and excludes dynamic imports', async (context) => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'app-entry-budget-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(path.join(root, 'feature'));
  await writeFile(path.join(root, 'app.js'), [
    "import './feature/a.js';",
    "void import('./deferred.js');",
  ].join('\n'));
  await writeFile(path.join(root, 'feature', 'a.js'), "import '../app.js';\n");
  await writeFile(path.join(root, 'deferred.js'), 'throw new Error("not initial");\n');

  const files = [...await initialGraph(path.join(root, 'app.js'), root)].sort();

  assert.deepEqual(files, [
    path.join(root, 'app.js'),
    path.join(root, 'feature', 'a.js'),
  ].sort());
});

test('lightweight routes keep the app static graph within 86,434 raw bytes', async () => {
  const files = [...await initialGraph(path.join(JS_ROOT, 'app.js'))].sort();
  const sizes = await Promise.all(files.map(file => stat(file).then(value => value.size)));
  const total = sizes.reduce((sum, size) => sum + size, 0);

  assert.ok(total <= MAX_INITIAL_BYTES,
    `app.js initial graph is ${total} bytes across ${files.length} modules:\n${files.join('\n')}`);
});
