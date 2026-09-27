import random
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles


app = FastAPI(title="WebSocket Game Demo")

STATIC_DIR = Path(__file__).parent / "static"
GAME_WIDTH = 640
GAME_HEIGHT = 360
PLAYER_SIZE = 28

# M02 keeps the shared game state and WebSocket connections in memory.
players: dict[str, dict[str, int | str]] = {}
active_connections: list[WebSocket] = []
connection_player_ids: dict[WebSocket, str] = {}
metrics_started_at = time.monotonic()
metrics = {
    "totalConnectionsOpened": 0,
    "incomingMessages": 0,
    "joinMessages": 0,
    "moveMessages": 0,
    "outgoingMessages": 0,
}

app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
# Keep the deployment URL working when the app is accessed directly in development.
app.mount("/websocket/static", StaticFiles(directory=STATIC_DIR), name="websocket-static")


@app.get("/")
async def index() -> FileResponse:
    """Serve the single-page frontend."""
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/metrics")
@app.get("/websocket/metrics")
async def metrics_page() -> FileResponse:
    """Serve the simple metrics dashboard."""
    return FileResponse(STATIC_DIR / "metrics.html")


def metrics_snapshot() -> dict[str, int]:
    return {
        "uptimeSeconds": int(time.monotonic() - metrics_started_at),
        "connectedPlayers": len(players),
        "currentWebSocketConnections": len(active_connections),
        **metrics,
    }


@app.get("/api/metrics")
@app.get("/websocket/api/metrics")
async def get_metrics() -> dict[str, int]:
    return metrics_snapshot()


@app.post("/api/metrics/reset")
@app.post("/websocket/api/metrics/reset")
async def reset_metrics() -> dict[str, bool]:
    global metrics_started_at

    metrics_started_at = time.monotonic()
    for metric_name in metrics:
        metrics[metric_name] = 0
    return {"ok": True}


async def broadcast_state() -> None:
    """Send the current player state to every connected WebSocket client."""
    payload = {"type": "state", "players": list(players.values())}

    # M03 workshop focus: broadcast the updated state to every active client.
    for connection in active_connections:
        await connection.send_json(payload)
        metrics["outgoingMessages"] += 1


@app.websocket("/ws")
@app.websocket("/websocket/ws")
async def websocket_endpoint(websocket: WebSocket) -> None:
    await websocket.accept()
    active_connections.append(websocket)
    metrics["totalConnectionsOpened"] += 1

    try:
        while True:
            data = await websocket.receive_json()
            metrics["incomingMessages"] += 1

            if data.get("type") == "join" and websocket not in connection_player_ids:
                metrics["joinMessages"] += 1
                name = str(data.get("name", "")).strip()[:30]
                if not name:
                    continue

                player_id = uuid.uuid4().hex
                players[player_id] = {
                    "id": player_id,
                    "name": name,
                    "x": random.randint(0, GAME_WIDTH - PLAYER_SIZE),
                    "y": random.randint(0, GAME_HEIGHT - PLAYER_SIZE),
                }
                connection_player_ids[websocket] = player_id
                print(f"[player joined] {name}")
                await broadcast_state()

            elif data.get("type") == "move":
                metrics["moveMessages"] += 1
                player_id = connection_player_ids.get(websocket)
                if not player_id:
                    continue

                player = players[player_id]
                player["x"] = max(0, min(int(data.get("x", player["x"])), GAME_WIDTH - PLAYER_SIZE))
                player["y"] = max(0, min(int(data.get("y", player["y"])), GAME_HEIGHT - PLAYER_SIZE))
                await broadcast_state()

    except WebSocketDisconnect:
        active_connections.remove(websocket)
        player_id = connection_player_ids.pop(websocket, None)
        if player_id:
            player = players.pop(player_id, None)
            print(f"[player left] {player['name']}")
            await broadcast_state()
