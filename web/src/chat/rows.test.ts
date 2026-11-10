import { expect, test } from "vitest";
import { buildChatRows } from "./rows";
import { message } from "../../tests/messages";

test("an older failed upload keeps its receipt after a later user message", () => {
  const rows = buildChatRows([
    message({ key: "failed", kind: "file", failed: true }),
    message({ key: "later" }),
  ], [], "idle");
  expect(rows.filter(r => r.type === "bubble" && r.showReceipt).map(r => r.key)).toEqual(["failed", "later"]);
});

test("only the newest successful user message shows a receipt", () => {
  const rows = buildChatRows([message({ key: "first", delivery: "sent" }), message({ key: "last", delivery: "read" })], [], "idle");
  expect(rows.filter(r => r.type === "bubble" && r.showReceipt).map(r => r.key)).toEqual(["last"]);
});

test("an agent response never gets a user delivery receipt", () => {
  const rows = buildChatRows([message({ key: "agent", role: "agent", id: "remote" })], [], "idle");
  expect(rows.filter(r => r.type === "bubble" && r.showReceipt)).toEqual([]);
});

test("pending work shows typing until a streaming answer is present", () => {
  expect(buildChatRows([], [], "thinking").map(r => r.type)).toEqual(["typing"]);
  expect(buildChatRows([message({ role: "agent", final: false })], [], "typing").map(r => r.type)).toEqual(["day", "bubble"]);
});
