import type { GatewayClient } from "./GatewayClient";
import type { GroqClient } from "./GroqClient";

/** Ce que le chat attend d'une connexion : un gateway Hermes, ou un compte Groq direct. */
export type ChatClient = GatewayClient | GroqClient;
