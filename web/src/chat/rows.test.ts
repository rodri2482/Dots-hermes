import { describe, expect, it } from "vitest";
import type { ChatMessage } from "../gateway/model";
import { buildChatRows, type ChatRow } from "./rows";

/** Une bulle de texte de l'utilisateur, décalée de [minutes] pour éviter les groupements. */
function userMessage(key: string, minutes: number, overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    key,
    id: null,
    type: "message",
    role: "user",
    kind: "text",
    text: `message ${key}`,
    ts: Date.UTC(2026, 10, 10, 12) + minutes * 60_000,
    seq: 0,
    turn: null,
    final: true,
    delivery: "sending",
    reactions: {},
    ...overrides,
  };
}

function bubbles(rows: ChatRow[]) {
  return rows.filter((r): r is Extract<ChatRow, { type: "bubble" }> => r.type === "bubble");
}

describe("buildChatRows receipts", () => {
  it("shows the receipt on the last user message only", () => {
    const rows = bubbles(
      buildChatRows([userMessage("m1", 0), userMessage("m2", 6), userMessage("m3", 12)], [], "idle"),
    );
    expect(rows.map((r) => r.showReceipt)).toEqual([false, false, true]);
  });

  it("shows the receipt of a failed upload, even an old one", () => {
    const failedOld = userMessage("m1", 0, { failed: true });
    const rows = bubbles(
      buildChatRows([failedOld, userMessage("m2", 6), userMessage("m3", 12)], [], "idle"),
    );
    expect(rows.map((r) => r.showReceipt)).toEqual([true, false, true]);
  });

  it("shows the receipt on a failed voice note", () => {
    const failedVoice = userMessage("v1", 0, {
      kind: "voice",
      durationMs: 4200,
      waveform: new Array(40).fill(0.5),
      failed: true,
    });
    const rows = bubbles(buildChatRows([failedVoice], [], "idle"));
    expect(rows).toHaveLength(1);
    expect(rows[0].showReceipt).toBe(true);
  });
});
