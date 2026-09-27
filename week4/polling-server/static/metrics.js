const API_BASE_PATH = "/polling/api";
const metricsList = document.querySelector("#metrics-list");
const metricsStatus = document.querySelector("#metrics-status");
const refreshButton = document.querySelector("#refresh-button");
const resetButton = document.querySelector("#reset-button");

async function loadMetrics() {
  try {
    const response = await fetch(`${API_BASE_PATH}/metrics`);
    if (!response.ok) throw new Error("Metrics request failed");

    const metrics = await response.json();
    metricsList.replaceChildren();
    for (const [name, value] of Object.entries(metrics)) {
      const term = document.createElement("dt");
      term.textContent = name;
      const description = document.createElement("dd");
      description.textContent = value;
      metricsList.append(term, description);
    }
    metricsStatus.textContent = "";
  } catch {
    metricsStatus.textContent = "Could not load metrics.";
  }
}

refreshButton.addEventListener("click", loadMetrics);

resetButton.addEventListener("click", async () => {
  try {
    const response = await fetch(`${API_BASE_PATH}/metrics/reset`, { method: "POST" });
    if (!response.ok) throw new Error("Metrics reset failed");
    await loadMetrics();
  } catch {
    metricsStatus.textContent = "Could not reset metrics.";
  }
});

loadMetrics();
