import { useState } from "preact/hooks";
import { pickGroqModel } from "../gateway/GroqClient";
import { strings } from "../i18n";
import { mergeAgents } from "../store";

/**
 * Sans téléphone ni gateway : une clé API Groq tapée ici. Elle est vérifiée (liste des modèles),
 * le meilleur modèle de conversation est choisi, puis l'agent « Claydot » est enregistré dans ce
 * navigateur. Un seul compte Groq : une nouvelle clé remplace l'ancienne.
 */
const t = strings({
  en: {
    title: "Use a Groq API key",
    hint: "No phone or server needed. Your key stays in this browser only: use it on your own computer.",
    label: "Groq API key",
    connect: "Connect",
    checking: "Checking…",
    getKey: "Get a Groq API key",
    invalid: "This key was refused by Groq.",
    failed: "Groq couldn’t be reached. Try again.",
    back: "Pair with my phone instead",
  },
  fr: {
    title: "Utiliser une clé API Groq",
    hint: "Sans téléphone ni serveur. Ta clé reste dans ce navigateur uniquement : utilise-le sur ton propre ordinateur.",
    label: "Clé API Groq",
    connect: "Se connecter",
    checking: "Vérification…",
    getKey: "Obtenir une clé API Groq",
    invalid: "Groq a refusé cette clé.",
    failed: "Impossible de joindre Groq. Réessaie.",
    back: "Appairer avec mon téléphone",
  },
});

export function GroqKeyForm({ onBack, onDone }: { onBack: () => void; onDone?: () => void }) {
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function connect(e: Event) {
    e.preventDefault();
    const trimmed = key.trim();
    if (!trimmed || busy) return;
    setBusy(true);
    setError(null);
    try {
      const model = await pickGroqModel(trimmed);
      mergeAgents([{
        url: "", token: "", session_id: "groq-" + crypto.randomUUID(), agent_id: "groq",
        name: "Claydot", species: "galet", palette: "tangerine", accessory: "winged_cap",
        provider: "groq", apiKey: trimmed, model,
      }]);
      setKey("");
      onDone?.();
    } catch (err) {
      setError(err instanceof Error && err.message === "invalid_key" ? t.invalid : t.failed);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form class="groq-form" onSubmit={connect}>
      <p class="t-title-sm">{t.title}</p>
      <p class="t-body-sm t-secondary">{t.hint}</p>
      <input
        class="groq-key"
        type="password"
        autocomplete="off"
        spellcheck={false}
        placeholder="gsk_…"
        aria-label={t.label}
        value={key}
        onInput={(e) => setKey((e.target as HTMLInputElement).value)}
      />
      {error && <p class="t-body-sm pair-error">{error}</p>}
      <button class="groq-connect" type="submit" disabled={!key.trim() || busy}>{busy ? t.checking : t.connect}</button>
      <div class="groq-links">
        <a href="https://console.groq.com/keys" target="_blank" rel="noopener noreferrer">{t.getKey}</a>
        <button type="button" class="groq-back" onClick={onBack}>{t.back}</button>
      </div>
    </form>
  );
}
