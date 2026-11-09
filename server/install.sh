#!/usr/bin/env bash
# Installe (ou met à jour) le plugin « dots-platform » dans une installation Hermes Agent.
#
#   bash server/install.sh [options]
#
# Ce que fait le script, sans rien casser de l'existant :
#   1. copie le plugin dans $HERMES_HOME/plugins/dots (l'ancienne version est sauvegardée HORS de
#      plugins/, sinon Hermes pourrait charger la sauvegarde à la place de la nouvelle) ;
#   2. l'active (hermes plugins enable dots-platform) ;
#   3. complète $HERMES_HOME/.env : jeton aléatoire, adresse d'écoute, propriétaire — seulement les
#      clés absentes, après sauvegarde du fichier ;
#   4. coupe les bulles texte de progression pour cette plateforme (les outils deviennent des sur-messages) ;
#   5. en option : transcription vocale locale (Whisper), clé Firebase pour le push, exemple Caddy ;
#   6. redémarre le gateway et vérifie que le plugin répond.
#
# Plusieurs agents indépendants : un profil Hermes par agent, et une installation par profil.
#   bash server/install.sh --profile ash --bind 127.0.0.1:8788 --domain hermes.example.com
# Chaque profil garde son jeton, son port, son historique et ses clés ; l'app se connecte à chacun.
#
# Options :
#   --profile NAME       profil Hermes à équiper ($HOME/.hermes/profiles/NAME) ; défaut : le profil principal
#   --agent-name NAME    nom de l'agent affiché par l'app (défaut : le nom du profil, sinon « Hermes »)
#   --service NAME       service systemd du gateway (défaut : hermes-gateway, ou hermes-gateway-NAME avec --profile)
#   --domain NAME        domaine public du gateway : affiche l'adresse wss:// et un Caddyfile prêt à l'emploi
#   --bind HOST:PORT     adresse d'écoute locale du plugin (défaut : 127.0.0.1:8787)
#   --voice [MODEL]      installe la transcription locale (faster-whisper) ; modèle : tiny|base|small (défaut : small)
#   --stt PROVIDER       moteur de transcription : local (faster-whisper, comme --voice) ou groq
#                        (API Whisper de Groq : rapide, rien à installer, mais l'audio quitte le serveur).
#                        La clé Groq n'est jamais passée en argument : exporte GROQ_API_KEY avant de lancer
#                        le script, ou saisis-la à l'invite (masquée) ; une clé déjà dans .env est gardée.
#   --stt-language LANG  langue de la transcription (défaut : fr ; "" = détection automatique)
#   --fcm-key FILE       clé JSON du compte de service Firebase, pour les notifications push
#   --web-origin URL     autre origine de l'app web à autoriser, en plus de https://dots-hermes.vercel.app
#                        (toujours autorisée) ; répétable
#   --rotate-token       génère un nouveau jeton même s'il en existe déjà un (l'app devra être ré-appairée)
#   --no-restart         ne redémarre pas le gateway (à faire soi-même ensuite)
#   --dry-run            affiche ce qui serait fait, sans rien modifier
#   -h, --help           cette aide
#
# Variables : HERMES_HOME (défaut ~/.hermes), HERMES_BIN (défaut : « hermes » dans le PATH).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_SRC="$SCRIPT_DIR/hermes-plugin-dots"
PLUGIN_FILES=(plugin.yaml __init__.py adapter.py guard.py)
TESTED_HERMES="0.22"

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
PROFILE=""
AGENT_NAME=""
SERVICE=""
DOMAIN=""
BIND="127.0.0.1:8787"
BIND_GIVEN=0
VOICE_MODEL=""
STT_PROVIDER=""
STT_LANGUAGE="fr"
FCM_KEY=""
WEB_ORIGINS=""
# App web officielle, toujours autorisée (voir web/ et docs/appairage-web.md).
DEFAULT_WEB_ORIGIN="https://dots-hermes.vercel.app"
ROTATE_TOKEN=0
RESTART=1
DRY_RUN=0

# -- Affichage ---------------------------------------------------------------------------------------

if [ -t 1 ]; then
    BOLD=$'\e[1m'; DIM=$'\e[2m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; RED=$'\e[31m'; RESET=$'\e[0m'
else
    BOLD=""; DIM=""; GREEN=""; YELLOW=""; RED=""; RESET=""
fi
step() { printf '\n%s▸ %s%s\n' "$BOLD" "$1" "$RESET"; }
ok()   { printf '  %s✓%s %s\n' "$GREEN" "$RESET" "$1"; }
warn() { printf '  %s!%s %s\n' "$YELLOW" "$RESET" "$1"; }
die()  { printf '%s✗ %s%s\n' "$RED" "$1" "$RESET" >&2; exit 1; }
run()  {
    # Sur stderr : reste visible même quand la commande réelle est appelée avec >/dev/null.
    if [ "$DRY_RUN" = 1 ]; then printf '  %s[dry-run]%s %s\n' "$DIM" "$RESET" "$*" >&2; else "$@"; fi
}

usage() { sed -n '2,/^set -euo/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; }

# -- Arguments ---------------------------------------------------------------------------------------

while [ $# -gt 0 ]; do
    case "$1" in
        --profile) PROFILE="${2:?--profile attend un nom de profil}"; shift 2 ;;
        --agent-name) AGENT_NAME="${2:?--agent-name attend un nom}"; shift 2 ;;
        --service) SERVICE="${2:?--service attend un nom de service}"; shift 2 ;;
        --domain) DOMAIN="${2:?--domain attend un nom de domaine}"; shift 2 ;;
        --bind) BIND="${2:?--bind attend HOST:PORT}"; BIND_GIVEN=1; shift 2 ;;
        --voice)
            if [ $# -gt 1 ] && [[ "$2" =~ ^(tiny|base|small|medium)$ ]]; then VOICE_MODEL="$2"; shift 2
            else VOICE_MODEL="small"; shift; fi ;;
        --stt) STT_PROVIDER="${2:?--stt attend local ou groq}"; shift 2 ;;
        --stt-language) STT_LANGUAGE="${2-}"; shift 2 ;;
        --fcm-key) FCM_KEY="${2:?--fcm-key attend un fichier}"; shift 2 ;;
        --web-origin) WEB_ORIGINS="${WEB_ORIGINS:+$WEB_ORIGINS,}${2:?--web-origin attend une URL https://…}"; shift 2 ;;
        --rotate-token) ROTATE_TOKEN=1; shift ;;
        --no-restart) RESTART=0; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "Option inconnue : $1 (voir --help)" ;;
    esac
done

# -- Profil ------------------------------------------------------------------------------------------

if [ -n "$PROFILE" ]; then
    [[ "$PROFILE" =~ ^[A-Za-z0-9_-]+$ ]] || die "--profile : lettres, chiffres, - et _ seulement (reçu : $PROFILE)."
    HERMES_HOME="$HOME/.hermes/profiles/$PROFILE"
    SERVICE="${SERVICE:-hermes-gateway-$PROFILE}"
    WS_ROUTE="/dots/$PROFILE/ws"
else
    SERVICE="${SERVICE:-hermes-gateway}"
    WS_ROUTE="/dots/ws"
fi

# -- Prérequis ---------------------------------------------------------------------------------------

step "Vérification de l'installation Hermes"
HERMES_BIN="${HERMES_BIN:-$(command -v hermes || true)}"
[ -z "$HERMES_BIN" ] && [ -x "$HOME/.local/bin/hermes" ] && HERMES_BIN="$HOME/.local/bin/hermes"
[ -n "$HERMES_BIN" ] || die "Commande « hermes » introuvable. Installe Hermes Agent, ou indique-la avec HERMES_BIN=/chemin/vers/hermes."
[ -d "$HERMES_HOME" ] || die "Dossier Hermes introuvable : $HERMES_HOME (règle HERMES_HOME si besoin)."
for f in "${PLUGIN_FILES[@]}"; do
    [ -f "$PLUGIN_SRC/$f" ] || die "Fichier du plugin manquant : $PLUGIN_SRC/$f (lance le script depuis le dépôt cloné)."
done
hermes() { HERMES_HOME="$HERMES_HOME" "$HERMES_BIN" "$@"; }
ok "hermes : $HERMES_BIN"
ok "dossier : $HERMES_HOME"

version="$(hermes --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1 || true)"
if [ -z "$version" ]; then
    warn "Version de Hermes inconnue : le plugin est testé avec $TESTED_HERMES.x."
elif [[ "$version" != "$TESTED_HERMES"* ]]; then
    warn "Hermes $version détecté : le plugin est testé avec $TESTED_HERMES.x. L'écran en direct, les crons et les pièces jointes s'appuient sur des modules internes de Hermes qui ont pu changer."
else
    ok "Hermes $version"
fi
[[ "$BIND" =~ ^[^:]+:[0-9]+$ ]] || die "--bind doit être de la forme HOST:PORT (reçu : $BIND)."
case "$STT_PROVIDER" in
    ""|groq) ;;
    local) VOICE_MODEL="${VOICE_MODEL:-small}" ;;
    *) die "--stt : local ou groq (reçu : $STT_PROVIDER)." ;;
esac
[ -z "$FCM_KEY" ] || [ -f "$FCM_KEY" ] || die "Clé Firebase introuvable : $FCM_KEY"

STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUPS="$HERMES_HOME/backups"

# -- 1. Plugin ---------------------------------------------------------------------------------------

step "Installation du plugin"
DEST="$HERMES_HOME/plugins/dots"
plugin_version() { sed -n 's/^version: *//p' "$1/plugin.yaml" 2>/dev/null | head -1 || true; }
NEW_VERSION="$(plugin_version "$PLUGIN_SRC")"
OLD_VERSION="$(plugin_version "$DEST")"
if [ -d "$DEST" ]; then
    run mkdir -p "$BACKUPS"
    # Surtout pas dans plugins/ : deux dossiers déclarant « dots-platform », Hermes pourrait garder l'ancien.
    run cp -a "$DEST" "$BACKUPS/plugin-dots.$STAMP"
    ok "ancienne version sauvegardée dans $BACKUPS/plugin-dots.$STAMP"
fi
run mkdir -p "$DEST"
for f in "${PLUGIN_FILES[@]}"; do run install -m 644 "$PLUGIN_SRC/$f" "$DEST/$f"; done
run rm -rf "$DEST/__pycache__"
ok "plugin ${NEW_VERSION:-?} copié dans $DEST${OLD_VERSION:+ (avant : $OLD_VERSION)}"
for other in "$HERMES_HOME"/plugins/*/plugin.yaml; do
    [ -f "$other" ] && [ "$(dirname "$other")" != "$DEST" ] && grep -q '^name: *dots-platform' "$other" \
        && warn "Une autre copie du plugin existe : $(dirname "$other"). Supprime-la, sinon Hermes risque de la charger."
done

run hermes plugins enable dots-platform >/dev/null
ok "plugin activé (dots-platform)"

# -- 2. Variables d'environnement --------------------------------------------------------------------

step "Configuration (.env)"
ENV_FILE="$HERMES_HOME/.env"
if [ -f "$ENV_FILE" ]; then
    run mkdir -p "$BACKUPS"
    run cp -p "$ENV_FILE" "$BACKUPS/env.$STAMP"
    ok "sauvegarde : $BACKUPS/env.$STAMP"
else
    run touch "$ENV_FILE"
fi

env_get() { [ -f "$ENV_FILE" ] && grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//' || true; }
env_set() {  # remplace la clé si elle existe, sinon l'ajoute
    if [ "$DRY_RUN" = 1 ]; then printf '  %s[dry-run]%s %s=…\n' "$DIM" "$RESET" "$1"; return; fi
    if grep -qE "^$1=" "$ENV_FILE"; then
        local tmp; tmp="$(mktemp)"
        awk -v k="$1" -v v="$2" 'BEGIN{FS=OFS="="} $1==k{print k"="v; next} {print}' "$ENV_FILE" > "$tmp" && cat "$tmp" > "$ENV_FILE" && rm -f "$tmp"
    else
        printf '%s=%s\n' "$1" "$2" >> "$ENV_FILE"
    fi
}
new_token() {
    if command -v openssl >/dev/null; then openssl rand -hex 24
    else python3 -c 'import secrets; print(secrets.token_hex(24))'; fi
}

TOKEN="$(env_get DOTS_TOKEN)"
if [ -z "$TOKEN" ] || [ "$ROTATE_TOKEN" = 1 ]; then
    TOKEN="$(new_token)"
    env_set DOTS_TOKEN "$TOKEN"
    ok "nouveau jeton généré (DOTS_TOKEN)"
elif [ "${#TOKEN}" -lt 24 ]; then
    warn "Le jeton actuel est court (${#TOKEN} caractères). Relance avec --rotate-token pour en générer un solide."
else
    ok "jeton existant conservé"
fi
# Une adresse déjà configurée est gardée, sauf si --bind en demande une autre.
CURRENT_BIND="$(env_get DOTS_BIND)"
# Chaque profil écoute sur son propre port : le 8787 par défaut est celui du profil principal.
if [ -n "$PROFILE" ] && [ "$BIND_GIVEN" = 0 ] && [ -z "$CURRENT_BIND" ]; then
    die "Profil « $PROFILE » : choisis un port libre avec --bind (ex. 127.0.0.1:8788), différent de celui des autres agents."
fi
if [ "$BIND_GIVEN" = 1 ] || [ -z "$CURRENT_BIND" ]; then
    [ "$CURRENT_BIND" = "$BIND" ] || env_set DOTS_BIND "$BIND"
else
    BIND="$CURRENT_BIND"
fi
[ -n "$(env_get DOTS_ALLOWED_USERS)" ] || env_set DOTS_ALLOWED_USERS owner
[ -n "$(env_get DOTS_HOME_CHANNEL)" ] || env_set DOTS_HOME_CHANNEL owner
[ -z "$AGENT_NAME" ] || env_set DOTS_AGENT_NAME "$AGENT_NAME"
# App web (appairage par QR) : le gateway refuse tout navigateur dont l'origine n'est pas listée.
# L'app web officielle est toujours autorisée ; s'y ajoutent les origines déjà configurées et les
# --web-origin, normalisées (minuscules, sans « / » final) et sans doublon.
ALL_ORIGINS="$(printf '%s\n' "$DEFAULT_WEB_ORIGIN" "$(env_get DOTS_WEB_ORIGINS)" "$WEB_ORIGINS" \
    | tr ',' '\n' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's#/*$##' | tr '[:upper:]' '[:lower:]' \
    | awk 'NF && !seen[$0]++' | paste -sd, -)"
[ "$(env_get DOTS_WEB_ORIGINS)" = "$ALL_ORIGINS" ] || env_set DOTS_WEB_ORIGINS "$ALL_ORIGINS"
ok "app web autorisée depuis $ALL_ORIGINS"
run chmod 600 "$ENV_FILE"
ok "écoute sur $BIND, propriétaire unique « owner »"

# -- 3. Affichage des outils -------------------------------------------------------------------------

step "Affichage des outils"
run hermes config set display.platforms.dots.tool_progress off >/dev/null
ok "outils affichés en sur-messages dans l'app (plus de bulles texte)"

# -- 4. Options --------------------------------------------------------------------------------------

if [ -n "$VOICE_MODEL" ]; then
    step "Messages vocaux (transcription locale)"
    run hermes pm install --extra stt-whisper
    run hermes config set stt.local.model "$VOICE_MODEL" >/dev/null
    run hermes config set stt.language "$STT_LANGUAGE" >/dev/null
    ok "faster-whisper « $VOICE_MODEL », langue « ${STT_LANGUAGE:-auto} »"
    [ "$VOICE_MODEL" = small ] && warn "Le modèle small demande ~700 Mo de RAM pendant une transcription ; sur un petit serveur, prends base ou ajoute du swap."
fi

if [ "$STT_PROVIDER" = groq ]; then
    step "Messages vocaux (transcription Groq)"
    # Ordre : variable exportée pour ce lancement > clé déjà dans .env > saisie masquée. Jamais affichée.
    GROQ_KEY="${GROQ_API_KEY:-}"
    if [ -n "$GROQ_KEY" ]; then
        env_set GROQ_API_KEY "$GROQ_KEY"
        ok "clé Groq enregistrée dans $ENV_FILE (depuis la variable GROQ_API_KEY)"
    elif [ -n "$(env_get GROQ_API_KEY)" ]; then
        GROQ_KEY="$(env_get GROQ_API_KEY)"
        ok "clé Groq existante conservée"
    elif [ -t 0 ] && [ "$DRY_RUN" = 0 ]; then
        read -rsp "  Clé API Groq (console.groq.com → API Keys ; la saisie reste masquée) : " GROQ_KEY; echo
        [ -n "$GROQ_KEY" ] || die "Aucune clé saisie."
        env_set GROQ_API_KEY "$GROQ_KEY"
        ok "clé Groq enregistrée dans $ENV_FILE"
    elif [ "$DRY_RUN" = 1 ]; then
        printf '  %s[dry-run]%s GROQ_API_KEY=… (demandée à l'"'"'installation réelle)
' "$DIM" "$RESET"
    else
        die "Clé Groq introuvable : lance « GROQ_API_KEY=gsk_… bash server/install.sh --stt groq » (avec une espace devant pour l'exclure de l'historique), ou ajoute-la dans $ENV_FILE."
    fi
    [ -z "$GROQ_KEY" ] || [[ "$GROQ_KEY" == gsk_* ]] || warn "La clé ne commence pas par « gsk_ » : vérifie qu'il s'agit bien d'une clé API Groq."
    run chmod 600 "$ENV_FILE"
    run hermes config set stt.provider groq >/dev/null
    run hermes config set stt.language "$STT_LANGUAGE" >/dev/null
    ok "transcription par Groq (whisper-large-v3-turbo), langue « ${STT_LANGUAGE:-auto} »"
    warn "Les vocaux sont envoyés à Groq pour être transcrits : ils ne restent plus seulement sur ce serveur."
fi

if [ -n "$FCM_KEY" ]; then
    step "Notifications push (Firebase)"
    run mkdir -p "$HERMES_HOME/platforms/dots"
    run install -m 600 "$FCM_KEY" "$HERMES_HOME/platforms/dots/fcm-service-account.json"
    ok "clé installée (lisible par $(id -un) seulement)"
fi

# -- 5. Redémarrage et vérification ------------------------------------------------------------------

HEALTH_URL="http://$BIND/dots/health"
if [ "$RESTART" = 1 ]; then
    step "Redémarrage du gateway"
    if systemctl --user cat "$SERVICE" >/dev/null 2>&1; then
        run systemctl --user restart "$SERVICE"
        ok "$SERVICE redémarré (systemd)"
    else
        warn "Service systemd « $SERVICE » introuvable : redémarre le gateway de ce profil toi-même (ou indique-le avec --service)."
        RESTART=0
    fi
fi

if [ "$RESTART" = 1 ] && [ "$DRY_RUN" = 0 ]; then
    printf '  attente du plugin'
    for _ in $(seq 1 30); do
        if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then printf '\n'; ok "le plugin répond sur $HEALTH_URL"; HEALTHY=1; break; fi
        printf '.'; sleep 1
    done
    if [ "${HEALTHY:-0}" != 1 ]; then
        printf '\n'
        warn "Pas de réponse sur $HEALTH_URL après 30 s. Regarde le journal : $HERMES_HOME/logs/gateway.log"
    fi
fi

# -- Récapitulatif -----------------------------------------------------------------------------------

step "C'est prêt"
if [ -n "$DOMAIN" ]; then
    cat <<EOF

  Reverse proxy — Caddyfile (TLS automatique, seul le WebSocket est exposé) :

    $DOMAIN {
    	handle $WS_ROUTE {
    		rewrite * /dots/ws
    		reverse_proxy $BIND
    	}
    	handle {
    		respond 404
    	}
    }

  Plusieurs agents sur le même domaine : un bloc « handle » par agent, chacun sa route et son port.

  Dans l'app (« Ajouter un agent » à partir du deuxième) :
    Gateway  ${BOLD}wss://$DOMAIN$WS_ROUTE${RESET}
EOF
else
    cat <<EOF

  Expose le plugin en HTTPS avec un reverse proxy (voir le README) ; relance avec --domain pour
  obtenir un Caddyfile prêt à l'emploi. Dans l'app :
    Gateway  ${BOLD}wss://<ton-domaine>$WS_ROUTE${RESET}
EOF
fi
if [ "$DRY_RUN" = 1 ]; then
    echo "    Jeton    (généré à l'installation réelle)"
else
    echo "    Jeton    ${BOLD}$(env_get DOTS_TOKEN)${RESET}"
fi
echo
echo "  App web  ${BOLD}$DEFAULT_WEB_ORIGIN${RESET} (scanne son QR code depuis l'app)"
echo
echo "  ${DIM}Le jeton est le seul identifiant de l'app : garde-le pour toi.${RESET}"
