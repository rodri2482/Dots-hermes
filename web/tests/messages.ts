import type { ChatMessage } from "../src/gateway/model";

export function message(patch: Partial<ChatMessage> = {}): ChatMessage {
  return {
    key: "local-1", id: null, type: "message", role: "user", kind: "text", text: "Hello",
    ts: new Date(2026, 10, 10, 12).getTime(), seq: 0, turn: null, final: true,
    delivery: "sending", reactions: {}, ...patch,
  };
}
