import assert from 'node:assert/strict';
import test from 'node:test';
import { disposeSurviveOnPageHide, mountSurvive, renderSurviveState } from '../../main/resources/static/js/survive.js';

const snapshot = {
  name: '<img src=x onerror=alert(1)>', health: 8, strength: 3, stamina: 11,
  experience: 1, experienceToNextLevel: 2, wood: 4, food: 1, inventoryCapacity: 10,
  enemyHealth: 0, status: 'EXPLORING', revision: 7, worldRevision: 10,
  shelters: 1, boats: 0, survivors: ['Alice', 'Bob'], events: ['Earlier', '<script>bad</script>'],
  actions: ['GATHER', 'HUNT', 'EAT', 'REST'], message: 'You gathered one wood.',
  survivorId: 'alice-public-id', recipients: [{ survivorId: 'bob-public-id', name: '<Bob>' }],
};

test('back/forward cache preserves mounted controls and final navigation releases them', () => {
  let disposalCount = 0;
  const mounted = { dispose() { disposalCount++; } };
  disposeSurviveOnPageHide(mounted, { persisted: true });
  assert.equal(disposalCount, 0);
  disposeSurviveOnPageHide(mounted, { persisted: false });
  assert.equal(disposalCount, 1);
});

function documentFixture() {
  const elements = new Map();
  function element() {
    return {
      textContent: '', hidden: false, disabled: false, value: 'Chris', children: [],
      listeners: new Map(), dataset: {}, focus() {},
      set innerHTML(_) { throw new Error('Untrusted content must never use innerHTML'); },
      addEventListener(name, handler) { this.listeners.set(name, handler); },
      removeEventListener(name) { this.listeners.delete(name); },
      replaceChildren(...children) { this.children = children; },
      querySelectorAll() { return []; },
    };
  }
  const buttons = ['GATHER', 'BUILD_BOAT', 'HUNT', 'ATTACK', 'DEFEND', 'FLEE'].map(action => {
    const button = element();
    button.dataset.action = action;
    button.closest = () => button;
    return button;
  });
  return {
    hidden: false, buttons,
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, element());
      return elements.get(id);
    },
    querySelectorAll() { return buttons; },
    createElement() { return element(); },
  };
}

test('giving targets a public identity, retains the selected recipient and uses text nodes', async () => {
  const documentRoot = documentFixture();
  const requests = [];
  const mounted = mountSurvive(documentRoot, async (url, options) => {
    requests.push({ url, options });
    return snapshot;
  });
  try {
    await new Promise(resolve => setImmediate(resolve));
    const recipient = documentRoot.getElementById('surviveRecipient');
    assert.equal(recipient.children[0].value, 'bob-public-id');
    assert.match(recipient.children[0].textContent, /<Bob>/);
    recipient.value = 'bob-public-id';
    documentRoot.getElementById('surviveGiftResource').value = 'WOOD';
    documentRoot.getElementById('surviveGiftQuantity').value = '2';
    renderSurviveState(documentRoot, snapshot);
    assert.equal(recipient.value, 'bob-public-id');
    assert.equal(documentRoot.getElementById('surviveGiftQuantity').value, '2');
    documentRoot.getElementById('surviveGift').listeners.get('submit')({ preventDefault() {} });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(requests[1].url, '/api/survive/v1/gifts');
    assert.deepEqual(JSON.parse(requests[1].options.body), {
      recipientId: 'bob-public-id', resource: 'WOOD', quantity: 2, revision: 7,
    });
    renderSurviveState(documentRoot, { ...snapshot, recipients: [] });
    assert.equal(documentRoot.getElementById('surviveGive').disabled, true);
  } finally { mounted.dispose(); }
});

test('background reads preserve gift input editing and unchanged recipient options', async () => {
  const documentRoot = documentFixture();
  let completeRead;
  let reads = 0;
  const mounted = mountSurvive(documentRoot, async () => {
    if (++reads === 1) return snapshot;
    return new Promise(resolve => { completeRead = resolve; });
  });
  try {
    await new Promise(resolve => setImmediate(resolve));
    const recipient = documentRoot.getElementById('surviveRecipient');
    const option = recipient.children[0];
    const amount = documentRoot.getElementById('surviveGiftQuantity');
    amount.value = '3';
    mounted.refresh();
    assert.equal(amount.disabled, false);
    assert.equal(recipient.disabled, false);
    assert.equal(documentRoot.getElementById('surviveGive').disabled, true);
    completeRead(snapshot);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(amount.value, '3');
    assert.equal(recipient.children[0], option);
  } finally { mounted.dispose(); }
});

test('renders survivor and shared camp using text nodes and server allowed actions', () => {
  const documentRoot = documentFixture();
  renderSurviveState(documentRoot, snapshot);
  assert.equal(documentRoot.getElementById('survivePlayerName').textContent, snapshot.name);
  assert.equal(documentRoot.getElementById('surviveHealth').value, 8);
  assert.equal(documentRoot.getElementById('surviveInventoryCount').textContent, '5 / 10');
  assert.equal(documentRoot.getElementById('surviveShelters').textContent, '1');
  assert.equal(documentRoot.getElementById('surviveEvents').children[0].textContent, '<script>bad</script>');
  assert.equal(documentRoot.buttons.find(button => button.dataset.action === 'BUILD_BOAT').disabled, true);
  assert.equal(documentRoot.buttons.find(button => button.dataset.action === 'GATHER').disabled, false);
  assert.equal(documentRoot.buttons.find(button => button.dataset.action === 'ATTACK').hidden, true);
  renderSurviveState(documentRoot, { ...snapshot, status: 'COMBAT', enemyHealth: 5, actions: ['ATTACK'] });
  assert.equal(documentRoot.getElementById('surviveEnemy').hidden, false);
  assert.equal(documentRoot.buttons.find(button => button.dataset.action === 'ATTACK').disabled, false);
  renderSurviveState(documentRoot, { ...snapshot, status: 'DEAD', actions: [] });
  assert.ok(documentRoot.buttons.every(button => button.disabled));
});

test('serializes actions with the server revision and reconciles failure without replay', async () => {
  const documentRoot = documentFixture();
  const requests = [];
  let rejectAction;
  const requestJson = async (url, options) => {
    requests.push({ url, options });
    if (options.method === 'POST') {
      return new Promise((_, reject) => { rejectAction = reject; });
    }
    return snapshot;
  };
  const mounted = mountSurvive(documentRoot, requestJson);
  try {
    await new Promise(resolve => setImmediate(resolve));
    const gather = documentRoot.buttons[0];
    const click = documentRoot.getElementById('surviveActions').listeners.get('click');
    click({ target: gather });
    click({ target: gather });
    assert.equal(requests.filter(request => request.options.method === 'POST').length, 1);
    assert.deepEqual(JSON.parse(requests[1].options.body), { action: 'GATHER', revision: 7 });
    assert.ok(documentRoot.buttons.every(button => button.disabled));
    rejectAction(new Error('Lost response'));
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(requests.length, 3);
    assert.equal(requests[2].url, '/api/survive/v1/game');
    assert.equal(documentRoot.getElementById('surviveError').hidden, false);
    assert.match(documentRoot.getElementById('surviveError').textContent, /Lost response/);
    assert.equal(gather.disabled, false);
  } finally { mounted.dispose(); }
});

test('no survivor shows join form and joining sends only the chosen name', async () => {
  const documentRoot = documentFixture();
  const requests = [];
  const mounted = mountSurvive(documentRoot, async (url, options) => {
    requests.push({ url, options });
    return options.method === 'POST' ? snapshot : {};
  });
  try {
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(documentRoot.getElementById('surviveJoin').hidden, false);
    assert.equal(documentRoot.getElementById('surviveGame').hidden, true);
    documentRoot.getElementById('surviveName').value = ' Alice ';
    documentRoot.getElementById('surviveJoin').listeners.get('submit')({ preventDefault() {} });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(JSON.parse(requests[1].options.body), { name: 'Alice' });
    assert.equal(documentRoot.getElementById('surviveJoin').hidden, true);
  } finally { mounted.dispose(); }
});
