# HTTP Polling Game Demo

This standalone project demonstrates the same multiplayer game through repeated HTTP polling instead of a persistent connection. It contains no WebSocket or push fallback.

## Requirements and setup

Python 3.11 or newer is required.

```bash
python -m venv .venv
```

Activate the virtual environment:

```bash
# macOS / Linux
source .venv/bin/activate

# Windows PowerShell
.venv\Scripts\Activate.ps1
```

Install dependencies and run the server:

```bash
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8000
```

Open <http://localhost:8000> in two browser windows or tabs to simulate two players. The polling server disables Uvicorn access logs in `app.py` so frequent state polls do not flood the terminal; player joins and leaves remain logged.

## HTTP API flow

- `POST /api/join` with `{ "name": "Alice" }` returns a server-generated `playerId`.
- `POST /api/move` with `playerId`, `x`, and `y` updates that player's position.
- `GET /api/state` returns the complete current player list; other players become visible through these repeated requests.
- `POST /api/leave` with `playerId` removes the player.

## Polling interval

`static/game.js` defines `const POLL_INTERVAL_MS = 100;` near the top and passes it directly to `setInterval`. Change it to `1000`, `500`, or `100` before reloading the page to demonstrate different polling frequencies.

## Metrics

`GET /api/metrics` reports uptime, players, total gameplay API requests, and per-endpoint request counts. `POST /api/metrics/reset` resets counters without removing players or clearing game state.

Open `/polling/metrics` to view the current metrics and reset them with a button.
