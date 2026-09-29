// index.html 의 <script> 를 Node vm 에서 가짜 DOM 으로 실행
import { readFileSync } from "node:fs";
import vm from "node:vm";

export function loadPage({ bridge = null, synth = undefined, storage = {} } = {}) {
  const html = readFileSync(new URL("../../index.html", import.meta.url), "utf8");
  const script = html.match(/<script>([\s\S]*)<\/script>/)[1];

  const els = new Map();
  const el = (id) => {
    if (!els.has(id)) {
      els.set(id, {
        id,
        textContent: "",
        value: id === "rate" ? "0.8" : "",
        hidden: false,
        files: null,
        listeners: {},
        classList: { toggle() {} },
        addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); },
        fire(type) { (this.listeners[type] || []).forEach((fn) => fn({ target: this })); },
        click() { this.fire("click"); },
      });
    }
    return els.get(id);
  };

  const store = new Map(Object.entries(storage));
  const window = { AndroidSpeech: bridge, speechSynthesis: synth };
  const ctx = {
    window,
    document: { getElementById: el, createElement: () => ({}) },
    localStorage: {
      getItem: (k) => (store.has(k) ? store.get(k) : null),
      setItem: (k, v) => store.set(k, String(v)),
      removeItem: (k) => store.delete(k),
    },
    setTimeout, clearTimeout, console, URL, AbortController,
    Image: class {},
    SpeechSynthesisUtterance: class { constructor(t) { this.text = t; } },
    fetch: async () => { throw new Error("fetch disabled in tests"); },
  };
  vm.createContext(ctx);
  vm.runInContext(script, ctx);
  const run = (code) => vm.runInContext(code, ctx);
  return { el, window, run };
}

export function fakeBridge() {
  const calls = [];
  return {
    calls,
    load: (json, rate) => calls.push(["load", JSON.parse(json).length, rate]),
    playFrom: (i) => calls.push(["playFrom", i]),
    pause: () => calls.push(["pause"]),
    setRate: (r) => calls.push(["setRate", r]),
  };
}
