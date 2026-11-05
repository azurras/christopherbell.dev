import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('the island places the main menu, commands, and log inside the viewport', () => {
  const template = readFileSync(new URL('../../main/resources/templates/survive.html', import.meta.url), 'utf8');
  const viewport = template.slice(template.indexOf('class="survive-world-viewport"'), template.indexOf('id="surviveWorldDirections"'));
  for (const id of ['surviveMainMenu', 'surviveCommandButton', 'surviveWorldActions', 'surviveRecentEvents', 'surviveWorldVitals']) {
    assert.ok(viewport.includes(`id="${id}"`), `${id} belongs inside the viewport`);
  }
  assert.doesNotMatch(template, /<aside\b/);
  assert.doesNotMatch(template, /surviveWorldNotice|surviveWorldNumbers|id="surviveMessage"/);
});
