import { afterEach, describe, expect, it, vi } from "vitest";
import { pickGroqModel, ReactionTag } from "./GroqClient";

describe("ReactionTag", () => {
  const run = (...parts: string[]) => {
    const tag = new ReactionTag();
    const text = parts.map((p) => tag.feed(p)).join("") + tag.flush();
    return [text, tag.reaction];
  };
  it("hides a tag split across chunks and keeps the emoji", () => expect(run("[[rea", "ct:🎉]] Yay", "!")).toEqual(["Yay!", "🎉"]));
  it("passes a plain reply through", () => expect(run("Hello ", "there")).toEqual(["Hello there", null]));
  it("ignores an unknown emoji", () => expect(run("[[react:🦄]] Hi")).toEqual(["Hi", null]));
});

describe("pickGroqModel", () => {
  afterEach(() => vi.unstubAllGlobals());
  const models = (ids: string[], status = 200) =>
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ data: ids.map((id) => ({ id })) }), { status })));

  it("prefers the best chat model the key can use", async () => {
    models(["whisper-large-v3", "llama-3.1-8b-instant", "openai/gpt-oss-120b"]);
    await expect(pickGroqModel("k")).resolves.toBe("openai/gpt-oss-120b");
  });
  it("reports a refused key", async () => {
    models([], 401);
    await expect(pickGroqModel("k")).rejects.toThrow("invalid_key");
  });
});
