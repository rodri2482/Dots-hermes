"""Limite les essais de jeton ratés sur le WebSocket (fork Claydot).

Le gateway est exposé sur Internet derrière un reverse proxy : sans limite, n'importe qui peut
essayer des jetons en boucle. Un jeton de 48 caractères hexadécimaux (192 bits, celui d'install.sh)
reste hors de portée d'une attaque en force brute, mais la limite coupe court au bruit dans les
journaux, à la charge inutile et protège un jeton plus court choisi à la main.

Règle : MAX_FAILURES échecs en WINDOW secondes depuis une même adresse la bloquent BLOCK secondes.
Pendant le blocage, toute connexion de cette adresse reçoit un 429 sans que le jeton soit examiné.
L'app Android traite un 429 comme un gateway injoignable : elle garde son appairage et réessaie avec
son backoff, au lieu d'oublier le jeton comme après un 401.

Adresse retenue : derrière le proxy, toutes les connexions arrivent de 127.0.0.1. L'adresse réelle
est alors le dernier élément de ``X-Forwarded-For`` (celui ajouté par le proxy lui-même ; Caddy le
pose par défaut). Une connexion locale SANS cet en-tête n'est jamais limitée : on ne peut pas la
distinguer du téléphone du propriétaire, et la bloquer permettrait à un attaquant de l'exclure.

Ce module n'importe rien de Hermes : il se teste seul (voir server/tests/test_guard.py).
"""

from __future__ import annotations

import ipaddress
import time
from collections import OrderedDict
from typing import Callable, Mapping, Optional

MAX_FAILURES = 10
WINDOW = 600.0  # secondes pendant lesquelles les échecs s'additionnent
BLOCK = 900.0  # durée du blocage une fois la limite atteinte
MAX_TRACKED = 4096  # adresses suivies au plus (les plus anciennes sont oubliées)
MIN_TOKEN_LENGTH = 32  # en dessous, le plugin prévient dans le journal


def _is_loopback(host: Optional[str]) -> bool:
    if not host:
        return False
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return host == "localhost"


def client_key(remote: Optional[str], headers: Mapping[str, str]) -> Optional[str]:
    """Adresse à limiter pour cette requête ; None quand on ne peut pas l'identifier sans risque.

    - connexion directe (pas de proxy) : l'adresse distante ;
    - via un proxy local : le dernier élément de ``X-Forwarded-For`` (sinon ``X-Real-IP``) ;
    - proxy local sans en-tête d'adresse : None (pas de limite, voir l'en-tête du module).
    """
    if not _is_loopback(remote):
        return remote or None
    forwarded = headers.get("X-Forwarded-For", "")
    if forwarded:
        last = forwarded.split(",")[-1].strip()
        if last:
            return last
    real = headers.get("X-Real-IP", "").strip()
    return real or None


class AuthGuard:
    """Compte les échecs par adresse et bloque les plus insistantes. Non partagé entre processus."""

    def __init__(self, *, max_failures: int = MAX_FAILURES, window: float = WINDOW, block: float = BLOCK,
                 max_tracked: int = MAX_TRACKED, clock: Callable[[], float] = time.monotonic):
        self.max_failures = max_failures
        self.window = window
        self.block = block
        self.max_tracked = max_tracked
        self._clock = clock
        # adresse -> [début de la fenêtre, échecs dans la fenêtre, fin du blocage (0 = aucun)]
        self._entries: "OrderedDict[str, list[float]]" = OrderedDict()

    def retry_after(self, key: Optional[str]) -> int:
        """Secondes de blocage restantes pour cette adresse (0 : elle peut se connecter)."""
        if key is None:
            return 0
        entry = self._entries.get(key)
        if entry is None:
            return 0
        remaining = entry[2] - self._clock()
        if remaining <= 0:
            if entry[2]:
                del self._entries[key]  # blocage terminé : on repart de zéro
            return 0
        return max(1, int(remaining + 0.999))

    def failure(self, key: Optional[str]) -> bool:
        """Enregistre un jeton refusé ; True si l'adresse vient d'être bloquée."""
        if key is None:
            return False
        now = self._clock()
        entry = self._entries.get(key)
        if entry is None or now - entry[0] > self.window:
            entry = [now, 0.0, 0.0]
        entry[1] += 1
        just_blocked = entry[1] >= self.max_failures and entry[2] <= now
        if just_blocked:
            entry[2] = now + self.block
        self._entries[key] = entry
        self._entries.move_to_end(key)
        while len(self._entries) > self.max_tracked:
            self._entries.popitem(last=False)
        return just_blocked

    def success(self, key: Optional[str]) -> None:
        """Jeton accepté : les échecs précédents de cette adresse sont oubliés."""
        if key is not None:
            self._entries.pop(key, None)

    def __len__(self) -> int:
        return len(self._entries)
