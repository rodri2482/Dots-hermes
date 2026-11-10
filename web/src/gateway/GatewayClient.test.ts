import { expect, test } from "vitest";
import { baseMime, sameUpload } from "./GatewayClient";
import { message } from "../../tests/messages";

test("modern replay requires the exact client identity", () => {
  expect(sameUpload(message(), message({ clientId: "local-1" }))).toBe(true);
  expect(sameUpload(message(), message({ clientId: "another-client" }))).toBe(false);
});

test("different upload kinds are never merged", () => {
  expect(sameUpload(message({ kind: "file" }), message({ kind: "voice" }))).toBe(false);
});

test("a legacy filename and size collision never identifies an upload", () => {
  const local = message({ kind: "file", name: "note.txt", size: 5 });
  expect(sameUpload(local, message({ kind: "file", name: "note.txt", size: 5 }))).toBe(false);
  expect(sameUpload(local, message({ kind: "file", name: "other.txt", size: 5 }))).toBe(false);
  expect(sameUpload(local, message({ kind: "file", name: "note.txt", size: 6 }))).toBe(false);
});

test("a legacy voice duration collision never identifies an upload", () => {
  const local = message({ kind: "voice", durationMs: 1200 });
  expect(sameUpload(local, message({ kind: "voice", durationMs: 1200 }))).toBe(false);
  expect(sameUpload(local, message({ kind: "voice", durationMs: 1300 }))).toBe(false);
});

test("MIME comparison ignores codec parameters, whitespace and letter case", () => {
  expect(baseMime(" Audio/WebM ; codecs=opus")).toBe("audio/webm");
  expect(baseMime("audio/ogg")).toBe("audio/ogg");
  expect(baseMime("")).toBe("");
});
