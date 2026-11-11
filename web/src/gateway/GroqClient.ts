import { removeAgent } from "../store";
import type { WebAgent } from "../types";
import { EMPTY_SNAPSHOT, MAIN_CHAT, type ChatMessage, type GatewaySnapshot } from "./model";

/**
 * Un compte Groq dans le navigateur : la même interface que GatewayClient pour le chat, mais les
 * messages partent directement à l'API de Groq (https://api.groq.com, CORS ouvert), sans gateway.
 * La clé reste dans le stockage de ce navigateur ; la conversation aussi.
 */

const API = "https://api.groq.com/openai/v1";
const TRANSCRIBE_MODEL = "whisper-large-v3-turbo";
const HISTORY_PREFIX = "claydot.groq.history.";
/** Messages renvoyés au modèle à chaque tour : assez pour le contexte, pas pour exploser le coût. */
const CONTEXT_MESSAGES = 30;
const REACTIONS = ["❤️", "👍", "😂", "😮", "🎉", "👀", "🔥", "😢"];

/** Modèles Groq préférés, comme dans l'app Android ; le premier présent dans le compte est choisi. */
const PREFERRED = ["openai/gpt-oss-120b", "llama-3.3-70b-versatile", "moonshotai/kimi-k2-instruct", "openai/gpt-oss-20b", "llama-3.1-8b-instant"];
const NOT_FOR_CHAT = ["whisper", "tts", "guard", "playai", "orpheus", "embed", "moderation", "transcribe"];

/** Vérifie la clé et choisit le modèle : la liste des modèles de ce compte, filtrée pour la conversation. */
export async function pickGroqModel(key: string): Promise<string> {
  const res = await fetch(`${API}/models`, { headers: { Authorization: `Bearer ${key}` } });
  if (res.status === 401) throw new Error("invalid_key");
  if (!res.ok) throw new Error("unavailable");
  const ids: string[] = ((await res.json()).data ?? []).map((m: { id?: string }) => m.id ?? "").filter(Boolean);
  const usable = ids.filter((id) => !NOT_FOR_CHAT.some((w) => id.toLowerCase().includes(w)));
  const model = PREFERRED.find((p) => usable.includes(p)) ?? usable.sort()[0];
  if (!model) throw new Error("no_model");
  return model;
}

function persona(name: string, lang: string): string {
  return [
    `You are ${name === "Claydot" ? "Claydot" : `${name}, a Claydot companion`}: a small, round companion made of soft orange clay, wearing a tiny golden helmet with two white wings, a nod to Hermes, the messenger. You are an AI and say so if asked.`,
    "Curious, warm, upbeat and loyal. Short, natural sentences, like texting a good friend. Lead with the answer. Be honest: never invent facts, numbers or links.",
    `Reply in the user's language${lang ? ` (the browser is set to ${lang})` : ""}.`,
    `Start every reply with a reaction to the user's latest message, written exactly as [[react:EMOJI]] where EMOJI is one of ${REACTIONS.join(" ")}, or [[react:none]]. The tag is hidden from the user: never mention it.`,
    `Now: ${new Date().toString()}.`,
  ].join("\n\n");
}

/** Retire la balise de réaction du début d'une réponse qui arrive par morceaux (comme ReactionTag sur Android). */
export class ReactionTag {
  private pending = "";
  private done = false;
  reaction: string | null = null;

  feed(delta: string): string {
    if (this.done) return delta;
    this.pending += delta;
    const text = this.pending.trimStart();
    const prefix = "[[react:";
    if (text.length < prefix.length) return prefix.startsWith(text) ? "" : this.release();
    if (!text.startsWith(prefix)) return this.release();
    const end = text.indexOf("]]");
    if (end < 0) return text.length > 48 ? this.release() : "";
    const emoji = text.slice(prefix.length, end).trim();
    this.reaction = REACTIONS.includes(emoji) ? emoji : null;
    this.done = true;
    return text.slice(end + 2).trimStart();
  }

  flush(): string {
    return this.done ? "" : this.release();
  }

  private release(): string {
    this.done = true;
    return this.pending;
  }
}

let counter = 0;
const newKey = () => `g${Date.now().toString(36)}${(counter++).toString(36)}`;

export class GroqClient {
  agent: WebAgent;
  /** Pas de pièces jointes ; le micro marche (transcription Whisper de Groq). */
  readonly media = false;
  readonly voice = true;
  private messages: ChatMessage[] = [];
  private activity: GatewaySnapshot["activity"] = "idle";
  private online = navigator.onLine;
  private snapshot: GatewaySnapshot = EMPTY_SNAPSHOT;
  private readonly listeners = new Set<() => void>();
  private abort: AbortController | null = null;
  private failedKey: string | null = null;

  constructor(agent: WebAgent) {
    this.agent = agent;
    try {
      this.messages = JSON.parse(localStorage.getItem(HISTORY_PREFIX + agent.session_id) ?? "[]");
    } catch {
      this.messages = [];
    }
    window.addEventListener("online", this.onNetwork);
    window.addEventListener("offline", this.onNetwork);
    this.publish();
  }

  private onNetwork = () => {
    this.online = navigator.onLine;
    this.publish();
  };

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): GatewaySnapshot => this.snapshot;

  private publish(save = false): void {
    this.snapshot = {
      ...EMPTY_SNAPSHOT,
      status: this.online ? "online" : "offline",
      serverName: "Groq",
      activeChat: MAIN_CHAT,
      messages: this.messages,
      tools: [],
      activity: this.activity,
      loaded: true,
      hasMore: false,
      loadingOlder: false,
      unread: 0,
    };
    if (save) {
      try {
        localStorage.setItem(HISTORY_PREFIX + this.agent.session_id, JSON.stringify(this.messages.slice(-400)));
      } catch {
        /* stockage plein ou bloqué : la conversation reste le temps de l'onglet */
      }
    }
    this.listeners.forEach((l) => l());
  }

  // Cycle de vie attendu par le registre : rien à ouvrir ni à garder éveillé.
  start(): void {}
  wake(): void {}
  setForeground(_foreground: boolean): void {}
  setActive(_active: boolean): void {}
  selectChat(_chat: string): void {}
  loadOlder(): void {}
  answerApproval(_key: string, _approve: boolean): void {}
  answerClarify(_key: string, _response: string): void {}
  setAgent(agent: WebAgent): void {
    this.agent = agent;
  }

  dispose(): void {
    this.abort?.abort();
    window.removeEventListener("online", this.onNetwork);
    window.removeEventListener("offline", this.onNetwork);
    this.listeners.clear();
  }

  sendFile(_file: File, _caption = ""): boolean {
    return false;
  }

  fetchAttachment(_id: string, _kind: "file" | "audio"): Promise<Blob> {
    return Promise.reject(new Error("unsupported"));
  }

  canRetry(key: string): boolean {
    return key === this.failedKey && this.activity === "idle";
  }

  retry(key: string, _confirmUncertain = false): boolean {
    if (!this.canRetry(key)) return false;
    const message = this.messages.find((m) => m.key === key);
    if (!message) return false;
    this.messages = this.messages.filter((m) => m.key !== key && m.turn !== key);
    this.send(message.text);
    return true;
  }

  discard(key: string): boolean {
    this.messages = this.messages.filter((m) => m.key !== key && m.turn !== key);
    if (this.failedKey === key) this.failedKey = null;
    this.publish(true);
    return true;
  }

  toggleReaction(key: string, emoji: string): void {
    this.messages = this.messages.map((m) =>
      m.key === key ? { ...m, reactions: { ...m.reactions, user: m.reactions.user === emoji ? undefined : emoji } } : m,
    );
    this.publish(true);
  }

  stop(): void {
    this.abort?.abort();
  }

  /** « Se déconnecter » : oublie l'agent, sa clé et sa conversation dans ce navigateur. */
  logout(): void {
    this.abort?.abort();
    try {
      localStorage.removeItem(HISTORY_PREFIX + this.agent.session_id);
    } catch {
      /* rien */
    }
    removeAgent(this.agent);
  }

  /** Vocal : transcrit par Groq Whisper, puis envoyé comme un message écrit. */
  sendVoice(blob: Blob, _durationMs: number, _waveform: number[]): boolean {
    if (this.activity !== "idle" || !this.agent.apiKey) return false;
    this.activity = "thinking";
    this.publish();
    const form = new FormData();
    form.append("model", TRANSCRIBE_MODEL);
    form.append("response_format", "json");
    form.append("file", blob, blob.type.includes("ogg") ? "voice.ogg" : "voice.webm");
    void fetch(`${API}/audio/transcriptions`, { method: "POST", headers: { Authorization: `Bearer ${this.agent.apiKey}` }, body: form })
      .then(async (res) => (res.ok ? ((await res.json()).text as string) : ""))
      .catch(() => "")
      .then((text) => {
        this.activity = "idle";
        if (text.trim()) this.send(text.trim());
        else this.publish();
      });
    return true;
  }

  send(text: string): void {
    const trimmed = text.trim();
    if (!trimmed || this.activity !== "idle" || !this.agent.apiKey) return;
    const user: ChatMessage = {
      key: newKey(), id: null, type: "message", role: "user", kind: "text", text: trimmed, ts: Date.now(),
      seq: 0, turn: null, final: true, delivery: "sent", reactions: {},
    };
    user.id = user.key;
    const answer: ChatMessage = {
      key: newKey(), id: null, type: "message", role: "agent", kind: "text", text: "", ts: Date.now(),
      seq: 0, turn: user.key, final: false, delivery: "read", reactions: {},
    };
    answer.id = answer.key;
    const history = this.messages.filter((m) => m.kind === "text" && m.text && !m.failed).slice(-CONTEXT_MESSAGES);
    this.failedKey = null;
    this.messages = [...this.messages, user];
    this.activity = "thinking";
    this.publish(true);
    void this.stream(user, answer, history);
  }

  private async stream(user: ChatMessage, answer: ChatMessage, history: ChatMessage[]): Promise<void> {
    const controller = new AbortController();
    this.abort = controller;
    const tag = new ReactionTag();
    let text = "";
    const body = {
      model: this.agent.model,
      stream: true,
      max_completion_tokens: 4096,
      messages: [
        { role: "system", content: persona(this.agent.name || "Claydot", navigator.language) },
        ...history.map((m) => ({ role: m.role === "user" ? "user" : "assistant", content: m.text })),
        { role: "user", content: user.text },
      ],
    };
    const update = (final: boolean) => {
      this.messages = this.messages.map((m) => (m.key === user.key && tag.reaction ? { ...m, reactions: { ...m.reactions, agent: tag.reaction } } : m));
      const shown = { ...answer, text, final };
      this.messages = this.messages.some((m) => m.key === answer.key)
        ? this.messages.map((m) => (m.key === answer.key ? shown : m))
        : text
          ? [...this.messages, shown]
          : this.messages;
    };
    try {
      const res = await fetch(`${API}/chat/completions`, {
        method: "POST",
        headers: { Authorization: `Bearer ${this.agent.apiKey}`, "Content-Type": "application/json" },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      if (!res.ok || !res.body) throw new Error(`http_${res.status}`);
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let cut: number;
        while ((cut = buffer.indexOf("\n")) >= 0) {
          const line = buffer.slice(0, cut).trim();
          buffer = buffer.slice(cut + 1);
          if (!line.startsWith("data:")) continue;
          const data = line.slice(5).trim();
          if (data === "[DONE]") continue;
          try {
            const delta = JSON.parse(data).choices?.[0]?.delta?.content;
            if (typeof delta === "string" && delta) {
              text += tag.feed(delta);
              this.activity = "typing";
              update(false);
              this.publish();
            }
          } catch {
            /* fragment illisible : ignoré */
          }
        }
      }
      text += tag.flush();
      update(true);
    } catch (error) {
      text += tag.flush();
      if (text) update(true);
      if (!(error instanceof DOMException && error.name === "AbortError")) {
        // Échec : le message de l'utilisateur passe en « Non envoyé », avec Réessayer / Effacer.
        this.messages = this.messages.map((m) => (m.key === user.key ? { ...m, failed: true, retryable: true } : m));
        this.failedKey = user.key;
      }
    } finally {
      if (this.abort === controller) this.abort = null;
      this.activity = "idle";
      this.publish(true);
    }
  }
}
