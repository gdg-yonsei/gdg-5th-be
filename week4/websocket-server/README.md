# WebSocket Game Demo

This standalone project demonstrates persistent, bidirectional WebSocket multiplayer state for the GDGoC polling-versus-WebSocket presentation.

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

Open <http://localhost:8000> in two browser windows or tabs. Each window is an independent WebSocket client connected to the same local server.

## WebSocket endpoint and message flow

The endpoint is `/ws` (`ws://localhost:8000/ws`, or `wss` under HTTPS). The client sends only a display name to join:

```json
{ "type": "join", "name": "Alice" }
```

It then sends movement with no player ID:

```json
{ "type": "move", "x": 120, "y": 200 }
```

The server generates and associates the player ID with that WebSocket connection. After joins, moves, and disconnects, it sends every active connection the shared state:

```json
{
  "type": "state",
  "players": [
    { "id": "abc123", "name": "Alice", "x": 120, "y": 200 }
  ]
}
```

Use WASD or arrow keys to move. The server clamps positions to the game area and remains authoritative.

## Workshop source locations

- `app.py` → `websocket_endpoint` shows accept, receive, join/move handling, and disconnect cleanup.
- `app.py` → `broadcast_state` contains the visible `for connection in active_connections` broadcast loop.
- `static/game.js` opens `/ws`, renders state, and sends movement.

## Metrics

`GET /api/metrics` reports uptime, players, WebSocket connections, and incoming/outgoing message counters. `POST /api/metrics/reset` resets counters without disconnecting players or clearing game state.

Open `/websocket/metrics` to view the current metrics and reset them with a button.
