import logging
import random
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles


app = FastAPI(title="Polling Game Demo")
logging.getLogger("uvicorn.access").disabled = True

STATIC_DIR = Path(__file__).parent / "static"
GAME_WIDTH = 640
GAME_HEIGHT = 360
PLAYER_SIZE = 28

# The polling version keeps the same shared game state in server memory.
players: dict[str, dict[str, int | str]] = {}
metrics_started_at = time.monotonic()
metrics = {
    "totalApiRequests": 0,
    "joinRequests": 0,
    "moveRequests": 0,
    "stateRequests": 0,
    "leaveRequests": 0,
}

app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
# Keep the deployment URL working when the app is accessed directly in development.
app.mount("/polling/static", StaticFiles(directory=STATIC_DIR), name="polling-static")


@app.get("/")
async def index() -> FileResponse:
    """Serve the single-page frontend."""
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/metrics")
@app.get("/polling/metrics")
async def metrics_page() -> FileResponse:
    """Serve the simple metrics dashboard."""
    return FileResponse(STATIC_DIR / "metrics.html")


def metrics_snapshot() -> dict[str, int]:
    return {
        "uptimeSeconds": int(time.monotonic() - metrics_started_at),
        "connectedPlayers": len(players),
        **metrics,
    }


@app.get("/api/metrics")
@app.get("/polling/api/metrics")
async def get_metrics() -> dict[str, int]:
    return metrics_snapshot()


@app.post("/api/metrics/reset")
@app.post("/polling/api/metrics/reset")
async def reset_metrics() -> dict[str, bool]:
    global metrics_started_at

    metrics_started_at = time.monotonic()
    for metric_name in metrics:
        metrics[metric_name] = 0
    return {"ok": True}


@app.post("/api/join")
@app.post("/polling/api/join")
async def join_game(data: dict[str, object]) -> dict[str, str]:
    metrics["totalApiRequests"] += 1
    metrics["joinRequests"] += 1
    name = str(data.get("name", "")).strip()[:30]
    if not name:
        raise HTTPException(status_code=400, detail="A player name is required.")

    player_id = uuid.uuid4().hex
    players[player_id] = {
        "id": player_id,
        "name": name,
        "x": random.randint(0, GAME_WIDTH - PLAYER_SIZE),
        "y": random.randint(0, GAME_HEIGHT - PLAYER_SIZE),
    }
    print(f"[player joined] {name}")
    return {"playerId": player_id}


@app.post("/api/move")
@app.post("/polling/api/move")
async def move_player(data: dict[str, object]) -> dict[str, bool]:
    metrics["totalApiRequests"] += 1
    metrics["moveRequests"] += 1
    player_id = str(data.get("playerId", ""))
    player = players.get(player_id)
    if not player:
        raise HTTPException(status_code=404, detail="Player not found.")

    player["x"] = max(0, min(int(data.get("x", player["x"])), GAME_WIDTH - PLAYER_SIZE))
    player["y"] = max(0, min(int(data.get("y", player["y"])), GAME_HEIGHT - PLAYER_SIZE))
    return {"ok": True}


@app.get("/api/state")
@app.get("/polling/api/state")
async def game_state() -> dict[str, list[dict[str, int | str]]]:
    metrics["totalApiRequests"] += 1
    metrics["stateRequests"] += 1
    return {"players": list(players.values())}


@app.post("/api/leave")
@app.post("/polling/api/leave")
async def leave_game(data: dict[str, object]) -> dict[str, bool]:
    metrics["totalApiRequests"] += 1
    metrics["leaveRequests"] += 1
    player_id = str(data.get("playerId", ""))
    player = players.pop(player_id, None)
    if player:
        print(f"[player left] {player['name']}")
    return {"ok": True}
