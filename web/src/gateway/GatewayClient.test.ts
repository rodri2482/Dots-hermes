import { describe, expect, it } from "vitest";
import type { ChatMessage } from "./model";
import { baseMime, sameUpload } from "./GatewayClient";

/** Une bulle locale, avec juste ce que [sameUpload] regarde. */
function message(overrides: Partial<ChatMessage>): ChatMessage {
  return {
    key: "k",
    id: null,
    type: "message",
    role: "user",
    kind: "text",
    text: "",
    ts: 0,
    seq: 0,
    turn: null,
    final: true,
    delivery: "sending",
    reactions: {},
    ...overrides,
  };
}

describe("sameUpload", () => {
  it("recognizes the same text, ignoring surrounding spaces", () => {
    expect(sameUpload(message({ kind: "text", text: " hi " }), message({ kind: "text", text: "hi" }))).toBe(true);
  });

  it("refuses a different text", () => {
    expect(sameUpload(message({ kind: "text", text: "hi" }), message({ kind: "text", text: "hello" }))).toBe(false);
  });

  it("recognizes a file by name and size", () => {
    const local = message({ kind: "file", name: "report.pdf", size: 1234 });
    const stored = message({ kind: "file", name: "report.pdf", size: 1234, id: "evt-1", text: "caption" });
    expect(sameUpload(local, stored)).toBe(true);
  });

  it("refuses a file of another size or name", () => {
    const local = message({ kind: "file", name: "report.pdf", size: 1234 });
    expect(sameUpload(local, message({ kind: "file", name: "report.pdf", size: 4321 }))).toBe(false);
    expect(sameUpload(local, message({ kind: "file", name: "other.pdf", size: 1234 }))).toBe(false);
  });

  it("recognizes a voice note by duration", () => {
    const local = message({ kind: "voice", durationMs: 4200 });
    const stored = message({ kind: "voice", durationMs: 4200, id: "evt-2" });
    expect(sameUpload(local, stored)).toBe(true);
    expect(sameUpload(local, message({ kind: "voice", durationMs: 5100 }))).toBe(false);
  });

  it("never matches across kinds", () => {
    expect(sameUpload(message({ kind: "text", text: "hi" }), message({ kind: "file", name: "hi", size: 2 }))).toBe(false);
  });
});

describe("baseMime", () => {
  it("drops the codec parameters", () => {
    expect(baseMime("audio/webm;codecs=opus")).toBe("audio/webm");
  });

  it("lowercases and trims", () => {
    expect(baseMime("  Audio/MP4 ")).toBe("audio/mp4");
  });

  it("keeps a plain type as is", () => {
    expect(baseMime("audio/ogg")).toBe("audio/ogg");
  });
});
