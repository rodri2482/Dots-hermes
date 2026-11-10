import { defineConfig } from "vitest/config";

/**
 * Tests unitaires (npm test). Environnement DOM : les modules importent i18n, qui touche
 * `document` et `navigator` dès le chargement ; happy-dom les fournit sans navigateur.
 */
export default defineConfig({
  test: {
    environment: "happy-dom",
  },
});
