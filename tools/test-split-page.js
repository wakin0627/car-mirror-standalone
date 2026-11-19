// Exercise split stream selection, decoding, touch routing and window dragging without a device.
const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
class Element {
  constructor(name) { this.name = name; this.id = name; this.style = {}; this.dataset = {}; this.hidden = true; this.handlers = {}; this.children = {}; this.width = 0; this.height = 0; this.textContent = ''; }
  addEventListener(name, fn) { (this.handlers[name] ||= []).push(fn); }
  removeEventListener(name, fn) { this.handlers[name] = (this.handlers[name] || []).filter(x => x !== fn); }
  fire(name, data = {}) { const event = { pointerId: 1, clientX: 20, clientY: 30, preventDefault() {}, stopPropagation() {}, ...data }; for (const fn of [...(this.handlers[name] || [])]) fn(event); }
  querySelector(name) { return this.children[name]; }
  querySelectorAll(name) { return name === ".bar button" ? [this.children[".back"], this.children[".min"], this.children[".max"], this.children[".close"]] : []; }
  appendChild(child) { (this.appended ||= []).push(child); }
  remove() {}
  click() { this.fire("click"); if (this.onclick) this.onclick(); }
  setPointerCapture() {}
  getBoundingClientRect() { return { left: 0, top: 0, width: 100, height: 200 }; }
  getContext() { return { drawImage() {} }; }
}
const items = Object.fromEntries(['desktop','message','reset','audio','full','disconnect','left','right','home','icons','tasks','drawer','choose','options','apps','dismiss','more','back','cancel-replace','replace-left','replace-right','task-left','task-right'].map(x => [x,new Element(x)]));
for (const key of ['left','right']) for (const name of ['canvas','.bar','.resize','.fps','.back','.min','.max','.close','.title']) items[key].children[name] = new Element(key + name);
const sockets = [];
class WebSocket {
  static OPEN = 1;
  constructor(url) { this.url = url; this.readyState = 1; this.sent = []; sockets.push(this); }
  send(value) { this.sent.push(value); }
  close() {}
}
class VideoDecoder {
  constructor(callbacks) { this.callbacks = callbacks; this.decodeQueueSize = 0; }
  configure(config) { this.config = config; }
  close() {}
  reset() {}
  decode(chunk) { this.lastChunk = chunk; this.callbacks.output({displayWidth:100,displayHeight:200,close(){}}); }
}
const document = {
  getElementById: id => items[id],
  createElement: name => new Element(name),
  querySelectorAll: selector => selector === '.window' ? [items.left,items.right] : [],
  documentElement: { requestFullscreen: () => Promise.resolve() },
  exitFullscreen() {},
};
const sandbox = {
  document, window: {VideoDecoder}, VideoDecoder, WebSocket,
  location: { protocol:'https:', host:'192.0.2.1:8443', search:'?key=abc123' },
  URLSearchParams, Uint8Array, ArrayBuffer, DataView,
  EncodedVideoChunk: class { constructor(value) { Object.assign(this,value); } },
  setInterval() {}, setTimeout() {}, performance: {now: () => 1}, console, fetch: () => Promise.resolve({ok:true,json:()=>Promise.resolve([])}),
};
const html = fs.readFileSync('app/src/main/assets/split.html','utf8');
vm.runInNewContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], sandbox);
assert.deepStrictEqual(sockets.map(x => x.url), [
  'wss://192.0.2.1:8443/stream?key=abc123',
  'wss://192.0.2.1:8443/stream2?key=abc123',
]);
const config = Uint8Array.from([0,1,0,0,0,1,0x67,0x42,0xe0,0x1e,0,0,0,1,0x68,0]);
const frame = Uint8Array.from([1,0,0,0,0,1,0x65,0x80]);
for (const socket of sockets) { socket.onmessage({data:config.buffer}); socket.onmessage({data:frame.buffer}); }
assert.strictEqual(items.left.children.canvas.width, 100);
assert.strictEqual(items.right.children.canvas.height, 200);
items.right.children.canvas.fire('pointerdown');
assert.strictEqual(sockets[0].sent.length, 0);
assert.strictEqual(sockets[1].sent.length, 1);
const touch = new DataView(sockets[1].sent[0]);
assert.strictEqual(touch.getUint8(1), 0);
assert.strictEqual(touch.getInt32(10), 20);
assert.strictEqual(touch.getInt32(14), 30);
items.right.children.canvas.fire('pointerup');
assert.strictEqual(new DataView(sockets[1].sent[1]).getUint8(1), 1);
items.right.children.canvas.fire('pointerdown', {clientX:120});
assert.strictEqual(sockets[1].sent.length, 2, 'touch outside the video must be ignored');
items.right.children['.back'].fire('click');
assert.strictEqual(sockets[1].sent[2], '{"action":"back"}');
sandbox.openApp({name:'地图', component:'com.example/.Maps'});
assert.strictEqual(items.left.hidden, false);
assert.strictEqual(items.left.children['.title'].textContent, '地图');
assert.strictEqual(sockets[0].sent[0], 'launch:com.example/.Maps');
sandbox.openApp({name:'视频', component:'com.example/.Video'});
assert.strictEqual(items.right.hidden, false);
assert.strictEqual(sockets[1].sent[3], 'launch:com.example/.Video');
sandbox.openApp({name:'第三个', component:'com.example/.Third'});
assert.strictEqual(items.choose.hidden, false);
assert.match(items['replace-left'].textContent, /地图/);
items['cancel-replace'].onclick();
assert.strictEqual(items.choose.hidden, true);
items.home.onclick();
assert.strictEqual(items.left.hidden, true);
assert.strictEqual(items.right.hidden, true);
items['task-right'].onclick();
assert.strictEqual(items.right.hidden, false);
items.apps.onclick();
assert.strictEqual(items.drawer.hidden, false);
items.dismiss.onclick();
items.back.onclick();
assert.strictEqual(sockets[1].sent[4], '{"action":"back"}');
sandbox.openApp({name:'第三个', component:'com.example/.Third'});
items['replace-left'].onclick();
assert.strictEqual(sockets[0].sent[1], 'launch:com.example/.Third');
assert.strictEqual(items['task-left'].textContent, '第三个');
items.left.children['.min'].onclick();
assert.strictEqual(items.left.hidden, true);
sandbox.openApp({name:'第三个', component:'com.example/.Third'});
assert.strictEqual(items.left.hidden, false);
console.log('Desktop: app launch, window controls, streams and touch routing passed');

let AudioPlayer;
class AudioWorkletProcessor { constructor() { this.port = {}; } }
vm.runInNewContext(fs.readFileSync('app/src/main/assets/audio-worklet.js', 'utf8'), {
  AudioWorkletProcessor, Float32Array, DataView, Math, sampleRate: 44100,
  registerProcessor(name, cls) { assert.strictEqual(name, 'car-audio-player'); AudioPlayer = cls; },
});
const audio = new AudioPlayer();
const pcm = new ArrayBuffer(4096 * 4), samples = new DataView(pcm);
for (let i = 0; i < 4096; i++) { samples.setInt16(i * 4, 8192, true); samples.setInt16(i * 4 + 2, -8192, true); }
audio.port.onmessage({data:pcm});
const l = new Float32Array(441), r = new Float32Array(441);
audio.process([], [[l, r]]);
assert.strictEqual(audio.available, 4096 - 480);
assert.strictEqual(l[100], 0.25);
assert.strictEqual(r[100], -0.25);
console.log('Audio worklet: stereo and 48 kHz to 44.1 kHz pacing passed');

