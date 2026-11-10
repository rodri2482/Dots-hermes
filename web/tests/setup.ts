import { vi } from "vitest";

// Seul l'initialiseur de langue touche le DOM dans ces tests de fonctions pures.
vi.stubGlobal("document", { documentElement: { lang: "en" } });
vi.stubGlobal("navigator", { language: "en-US" });
