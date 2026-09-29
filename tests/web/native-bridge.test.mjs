import test from "node:test";
import assert from "node:assert/strict";
import { loadPage, fakeBridge } from "./harness.mjs";

const ANSWER = "Ⅰ. 소재\n* 항목 A\n* 항목 B\nⅡ. 물음 1\n1. 요금제 A\n* 예산식\nⅢ. 결론\n* 끝";

function appPage() {
  const bridge = fakeBridge();
  const page = loadPage({ bridge });
  page.run(`lastAnswer = ${JSON.stringify(ANSWER)}`);
  return { ...page, bridge };
}

test("app mode shows no browser-speech warning", () => {
  const { el } = appPage();
  assert.doesNotMatch(el("voiceWarn").textContent, /지원하지 않습니다/);
});

test("browser mode without speechSynthesis still warns", () => {
  const { el } = loadPage({ bridge: null, synth: undefined });
  assert.match(el("voiceWarn").textContent, /지원하지 않습니다/);
});

test("play sends lines once, then plays from current line", () => {
  const { el, bridge } = appPage();
  el("btnPlay").click();
  assert.deepEqual(bridge.calls, [["load", 8, 0.8], ["playFrom", 0]]);
});

test("native state updates status and play button", () => {
  const { el, window } = appPage();
  el("btnPlay").click();
  window.onNativeSpeech({ state: "playing", pos: 2, total: 8 });
  assert.equal(el("status").textContent, "읽는 중 · 3/8줄");
  assert.equal(el("btnPlay").textContent, "⏸ 일시정지");
});

test("section navigation uses synced position and does not resend lines", () => {
  const { el, window, bridge } = appPage();
  el("btnPlay").click();
  window.onNativeSpeech({ state: "playing", pos: 4, total: 8 }); // Ⅱ 목차(3) 안
  el("btnPrevSection").click();
  el("btnNextSection").click();
  assert.deepEqual(bridge.calls.slice(2), [["playFrom", 3], ["playFrom", 6]]);
});

test("pause goes to native, not speechSynthesis", () => {
  const { el, window, bridge } = appPage();
  el("btnPlay").click();
  window.onNativeSpeech({ state: "playing", pos: 0, total: 8 });
  el("btnPlay").click();
  assert.deepEqual(bridge.calls.at(-1), ["pause"]);
});

test("rate slider forwards to native", () => {
  const { el, bridge } = appPage();
  el("rate").value = "0.3";
  el("rate").fire("input");
  assert.deepEqual(bridge.calls.at(-1), ["setRate", 0.3]);
});

test("ended resets UI to idle", () => {
  const { el, window } = appPage();
  el("btnPlay").click();
  window.onNativeSpeech({ state: "ended", pos: 0, total: 8 });
  assert.equal(el("status").textContent, "대기");
  assert.equal(el("btnPlay").textContent, "▶ 재생");
});

test("native message is shown with position", () => {
  const { el, window } = appPage();
  el("btnPlay").click();
  window.onNativeSpeech({ state: "playing", pos: 7, total: 8, message: "마지막 줄입니다" });
  assert.equal(el("status").textContent, "마지막 줄입니다 · 8/8줄");
});

test("new answer re-sends load", () => {
  const { el, run, bridge } = appPage();
  el("btnPlay").click();
  run(`lastAnswer = "Ⅰ. 새 답안\\n* 하나"`);
  el("btnReplay").click();
  assert.deepEqual(bridge.calls.slice(2), [["load", 2, 0.8], ["playFrom", 0]]);
});

test("quiet error message keeps error status through native updates", () => {
  const { el, window, run } = appPage();
  run(`reportError("401 인증 실패", "키 오류")`);
  window.onNativeSpeech({ state: "playing", pos: 0, total: 1 });
  window.onNativeSpeech({ state: "ended", pos: 0, total: 1 });
  assert.equal(el("status").textContent, "오류: 401 인증 실패");
});

test("native notice is shown in the warning area", () => {
  const { el, window } = appPage();
  window.onNativeNotice("⚠ 한국어 음성이 없습니다.");
  assert.equal(el("voiceWarn").hidden, false);
  assert.match(el("voiceWarn").textContent, /한국어 음성이 없습니다/);
});
