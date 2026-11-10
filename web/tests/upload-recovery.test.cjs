// Harnais navigateur minimal : les vraies actions du client reçoivent les trames d'un faux gateway.
const { test, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const ts = require('typescript');
require.extensions['.ts'] = (module, filename) => module._compile(ts.transpileModule(
  fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } },
).outputText, filename);

global.window = global;
global.document = { documentElement: {} };
const readers = [];
global.FileReader = class {
  readAsDataURL(blob) { this.blob = blob; readers.push(this); }
  async complete() {
    this.result = `data:${this.blob.type};base64,${Buffer.from(await this.blob.arrayBuffer()).toString('base64')}`;
    this.onload();
    await new Promise(setImmediate);
  }
};
class GatewaySocket {
  static OPEN = 1;
  static CLOSED = 3;
  static instances = [];
  constructor() { this.readyState = 0; this.frames = []; GatewaySocket.instances.push(this); }
  send(data) { this.frames.push(JSON.parse(data)); }
  close() { this.readyState = 3; }
  receive(frame) { this.onmessage({ data: JSON.stringify(frame) }); }
  welcome(history = [], dots = []) { this.readyState = 1; this.onopen(); this.receive({ type: 'welcome', reset: true, history, dots, seq: 0 }); }
  drop() { this.readyState = 3; this.onclose({ code: 1006 }); }
}
global.WebSocket = GatewaySocket;
const { GatewayClient } = require('../src/gateway/GatewayClient.ts');
const clients = [];
afterEach(() => { clients.splice(0).forEach(c => c.dispose()); readers.length = 0; });
function connected() {
  const client = new GatewayClient({ url: 'ws://mock.invalid/dots/ws', token: 'test-token', session_id: 'test', name: 'Test' });
  clients.push(client);
  client.start();
  const socket = GatewaySocket.instances.at(-1);
  socket.welcome();
  return { client, socket };
}
async function upload(client) {
  assert.equal(client.sendFile(new File(['hello'], 'note.txt', { type: 'text/plain' })), true);
  await readers.at(-1).complete();
  return client.getSnapshot().messages.at(-1);
}

test('gateway-rejected upload can be removed, never retried, and releases its preview', async () => {
  const { client, socket } = connected();
  const message = await upload(client);
  socket.receive({ type: 'error', code: 'bad_file', client_id: message.key });
  assert.equal(client.getSnapshot().messages.at(-1).failed, true);
  assert.equal(client.canRetry(message.key), false);
  assert.equal(client.retry(message.key), false);
  socket.drop();
  client.wake();
  const reconnected = GatewaySocket.instances.at(-1);
  reconnected.welcome();
  assert.equal(reconnected.frames.some(f => f.type === 'file'), false);
  assert.equal((await fetch(message.localUrl)).status, 200);
  assert.equal(client.discard(message.key), true);
  assert.equal(client.getSnapshot().messages.length, 0);
  await assert.rejects(fetch(message.localUrl));
});

test('a dropped upload retries with its original id and payload after reconnection', async () => {
  const { client, socket } = connected();
  const message = await upload(client);
  socket.drop();
  assert.equal(client.getSnapshot().messages.at(-1).failed, true);
  assert.equal(client.canRetry(message.key), true);
  assert.equal(client.retry(message.key), true);
  assert.equal(client.getSnapshot().messages.at(-1).failed, false);
  client.wake();
  const reconnected = GatewaySocket.instances.at(-1);
  reconnected.welcome();
  const frames = reconnected.frames.filter(f => f.type === 'file');
  assert.equal(frames.length, 1);
  assert.equal(frames[0].client_id, message.key);
  assert.equal(frames[0].data, 'aGVsbG8=');
  reconnected.receive({ type: 'ack', client_id: message.key, id: 'stored', seq: 1 });
  assert.equal(client.retry(message.key), false);
  assert.equal(client.discard(message.key), false);
});

test('discarded upload is not sent again when the gateway reconnects', async () => {
  const { client, socket } = connected();
  const message = await upload(client);
  socket.drop();
  assert.equal(client.discard(message.key), true);
  client.wake();
  const reconnected = GatewaySocket.instances.at(-1);
  reconnected.welcome();
  assert.equal(reconnected.frames.some(f => f.type === 'file'), false);
  assert.equal(client.getSnapshot().messages.length, 0);
});

test('automatic reconnect still resends recoverable uploads', async () => {
  const { client, socket } = connected();
  const message = await upload(client);
  socket.drop();
  client.wake();
  const reconnected = GatewaySocket.instances.at(-1);
  reconnected.welcome();
  assert.equal(reconnected.frames.filter(f => f.type === 'file').length, 1);
  assert.equal(client.getSnapshot().messages.at(-1).failed, false);
  assert.equal(client.canRetry(message.key), false);
});

test('legacy replay with the same filename and size never consumes a different pending upload', async () => {
  const { client, socket } = connected();
  const local = await upload(client);
  socket.drop(); client.wake();
  const reconnected = GatewaySocket.instances.at(-1);
  reconnected.welcome([{ type: 'message', id: 'older', role: 'user', kind: 'file', name: 'note.txt', size: 5, ts: 1, seq: 1, final: true }]);
  assert.equal(reconnected.frames.filter(f => f.type === 'file' && f.client_id === local.key).length, 0);
  assert.equal(client.getSnapshot().messages.length, 2);
  assert.equal(client.getSnapshot().messages.find(m => m.id === 'older').localUrl, undefined);
  assert.equal(client.getSnapshot().messages.find(m => m.key === local.key).uncertain, true);
  assert.equal(client.retry(local.key), false);
  assert.equal(client.retry(local.key, true), true);
  assert.equal(reconnected.frames.filter(f => f.type === 'file' && f.client_id === local.key).length, 1);
});

test('modern replay reconciles exactly by client_id without resending an accepted upload', async () => {
  const { client, socket } = connected();
  const older = await upload(client);
  const local = await upload(client);
  socket.drop(); client.wake();
  const reconnected = GatewaySocket.instances.at(-1);
  reconnected.welcome([{ type: 'message', id: 'stored', client_id: local.key, role: 'user', kind: 'file', name: 'note.txt', size: 5, ts: 1, seq: 1, final: true }]);
  assert.deepEqual(reconnected.frames.filter(f => f.type === 'file').map(f => f.client_id), [older.key]);
  assert.equal(client.getSnapshot().messages.length, 2);
  assert.equal(client.getSnapshot().messages.find(m => m.key === local.key).id, 'stored');
});

test('a legacy re-ack reconciles the duplicate server row by its confirmed id', async () => {
  const { client, socket } = connected();
  const local = await upload(client);
  socket.drop(); client.wake();
  const reconnected = GatewaySocket.instances.at(-1);
  reconnected.welcome([{ type: 'message', id: 'stored', role: 'user', kind: 'file', name: 'note.txt', size: 5, ts: 1, seq: 1, final: true }]);
  reconnected.receive({ type: 'ack', id: 'stored', client_id: local.key, seq: 1 });
  assert.equal(client.getSnapshot().messages.length, 1);
  assert.equal(client.getSnapshot().messages[0].localUrl, local.localUrl);
  assert.equal(!!client.getSnapshot().messages[0].uncertain, false);
});

test('new uploads after disposal never allocate previews or appear in the thread', () => {
  const { client } = connected();
  client.dispose();
  assert.equal(client.sendFile(new File(['hello'], 'note.txt')), false);
  assert.equal(client.sendVoice(new Blob(['audio'], { type: 'audio/webm' }), 500, []), false);
  assert.equal(client.getSnapshot().messages.length, 0);
});

test('removing a sub-dot while encoding prevents sending its upload into the main chat', async () => {
  const { client, socket } = connected();
  socket.welcome([], [{ id: 'dot-1', name: 'Test dot', species: 'galet', palette: 'sky', accessory: 'none' }]);
  client.selectChat('dot-1');
  client.sendFile(new File(['hello'], 'note.txt'));
  const pending = client.getSnapshot().messages.at(-1);
  socket.receive({ type: 'dots', dots: [] });
  await readers.at(-1).complete();
  assert.equal(socket.frames.some(f => f.type === 'file'), false);
  await assert.rejects(fetch(pending.localUrl));
});

test('a history reset releases previews for confirmed rows it replaces', async () => {
  const { client, socket } = connected();
  const local = await upload(client);
  socket.receive({ type: 'ack', id: 'stored', client_id: local.key, seq: 1 });
  assert.equal((await fetch(local.localUrl)).status, 200);
  socket.welcome();
  await assert.rejects(fetch(local.localUrl));
});

test('disposal during encoding releases the preview and never sends its completed payload', async () => {
  const { client, socket } = connected();
  client.sendFile(new File(['hello'], 'note.txt'));
  const local = client.getSnapshot().messages.at(-1);
  client.dispose();
  await readers.at(-1).complete();
  assert.equal(socket.frames.some(f => f.type === 'file'), false);
  await assert.rejects(fetch(local.localUrl));
});
