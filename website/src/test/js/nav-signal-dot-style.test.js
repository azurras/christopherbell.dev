import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const css = readFileSync('website/src/main/resources/static/css/main.css', 'utf8');

test('void nav signal dot blinks like a slow router status light', () => {
  assert.match(css, /\.void-signal-dot\s*\{[^}]*animation:\s*voidSignalBlink\s+2\.8s\s+ease-in-out\s+infinite/s);
  assert.match(css, /@keyframes\s+voidSignalBlink\s*\{/);
  assert.match(css, /50%\s*\{[^}]*opacity:\s*0\.35/s);
});
