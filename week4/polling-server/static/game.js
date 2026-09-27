const POLL_INTERVAL_MS = 100;
const MOVEMENT_STEP = 10;
const GAME_WIDTH = 640;
const GAME_HEIGHT = 360;
const PLAYER_SIZE = 28;
const API_BASE_PATH = "/polling/api";

const joinForm = document.querySelector("#join-form");
const nameInput = document.querySelector("#player-name");
const joinPanel = document.querySelector(".join-panel");
const gamePanel = document.querySelector("#game-panel");
const gameArea = document.querySelector("#game-area");
const playerCount = document.querySelector("#player-count");
const connectionStatus = document.querySelector("#connection-status");

let playerId;
let knownPlayers = [];
let pollTimer;

joinForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const name = nameInput.value.trim();
  if (!name) return;

  try {
    const response = await fetch(`${API_BASE_PATH}/join`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
    });
    if (!response.ok) throw new Error("Join failed");

    ({ playerId } = await response.json());
    joinPanel.classList.add("hidden");
    gamePanel.classList.remove("hidden");
    pollState();
    pollTimer = window.setInterval(pollState, POLL_INTERVAL_MS);
  } catch {
    connectionStatus.textContent = "Could not join the game server.";
  }
});

async function pollState() {
  try {
    const response = await fetch(`${API_BASE_PATH}/state`);
    if (!response.ok) throw new Error("State request failed");
    const state = await response.json();
    renderPlayers(state.players);
  } catch {
    connectionStatus.textContent = "Could not load game state.";
  }
}

function renderPlayers(players) {
  knownPlayers = players;
  playerCount.textContent = `Players: ${players.length}`;
  gameArea.replaceChildren();

  for (const player of players) {
    const playerElement = document.createElement("div");
    playerElement.className = "player";
    playerElement.style.left = `${player.x}px`;
    playerElement.style.top = `${player.y}px`;

    const nameElement = document.createElement("span");
    nameElement.className = "player-name";
    nameElement.textContent = player.name;
    playerElement.append(nameElement);
    gameArea.append(playerElement);
  }
}

document.addEventListener("keydown", async (event) => {
  if (!playerId) return;

  const moveByKey = {
    w: [0, -MOVEMENT_STEP],
    arrowup: [0, -MOVEMENT_STEP],
    s: [0, MOVEMENT_STEP],
    arrowdown: [0, MOVEMENT_STEP],
    a: [-MOVEMENT_STEP, 0],
    arrowleft: [-MOVEMENT_STEP, 0],
    d: [MOVEMENT_STEP, 0],
    arrowright: [MOVEMENT_STEP, 0],
  };
  const movement = moveByKey[event.key.toLowerCase()];
  if (!movement) return;

  event.preventDefault();
  const localPlayer = knownPlayers.find((player) => player.id === playerId);
  if (!localPlayer) return;

  localPlayer.x = Math.max(0, Math.min(localPlayer.x + movement[0], GAME_WIDTH - PLAYER_SIZE));
  localPlayer.y = Math.max(0, Math.min(localPlayer.y + movement[1], GAME_HEIGHT - PLAYER_SIZE));
  renderPlayers(knownPlayers);

  try {
    await fetch(`${API_BASE_PATH}/move`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ playerId, x: localPlayer.x, y: localPlayer.y }),
    });
  } catch {
    connectionStatus.textContent = "Could not send movement.";
  }
});

window.addEventListener("beforeunload", () => {
  window.clearInterval(pollTimer);
  if (!playerId) return;

  fetch(`${API_BASE_PATH}/leave`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ playerId }),
    keepalive: true,
  });
});
