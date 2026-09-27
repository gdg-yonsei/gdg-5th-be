const joinForm = document.querySelector("#join-form");
const nameInput = document.querySelector("#player-name");
const joinPanel = document.querySelector(".join-panel");
const gamePanel = document.querySelector("#game-panel");
const gameArea = document.querySelector("#game-area");
const playerCount = document.querySelector("#player-count");
const connectionStatus = document.querySelector("#connection-status");
const MOVEMENT_STEP = 10;
const GAME_WIDTH = 640;
const GAME_HEIGHT = 360;
const PLAYER_SIZE = 28;
const WEBSOCKET_PATH = "/websocket/ws";

let socket;
let playerId;
let knownPlayers = [];

joinForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const name = nameInput.value.trim();
  if (!name) return;

  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  socket = new WebSocket(`${protocol}://${window.location.host}${WEBSOCKET_PATH}`);

  socket.addEventListener("open", () => {
    socket.send(JSON.stringify({ type: "join", name }));
  });

  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.type === "state") renderPlayers(message.players);
  });

  socket.addEventListener("close", () => {
    connectionStatus.textContent = "Connection closed.";
  });

  socket.addEventListener("error", () => {
    connectionStatus.textContent = "Could not connect to the game server.";
  });
});

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

    if (!playerId && player.name === nameInput.value.trim()) playerId = player.id;
  }

  joinPanel.classList.add("hidden");
  gamePanel.classList.remove("hidden");
}

document.addEventListener("keydown", (event) => {
  if (!socket || socket.readyState !== WebSocket.OPEN || !playerId) return;

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
  socket.send(JSON.stringify({ type: "move", x: localPlayer.x, y: localPlayer.y }));
});
