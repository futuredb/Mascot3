const $ = (selector) => document.querySelector(selector);
const form = $("#generator");
const planButton = $("#plan-button");
const createButton = $("#create-button");
const planResult = $("#plan-result");
const dialog = $("#confirm-dialog");
let lastPlan = null;
let pollTimer = null;

function payload() {
  return {
    subject: $("#subject").value.trim(),
    brief: $("#brief").value.trim(),
    name: $("#name").value.trim(),
    subjectKind: $("#subject-kind").value,
  };
}

async function api(url, options = {}) {
  const response = await fetch(url, { headers: { "Content-Type": "application/json" }, ...options });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
  return data;
}

async function health() {
  try {
    const data = await api("/api/health");
    $("#system-label").textContent = data.paidReady ? "v2.5 · API готов" : "v2.5 · нужен API-ключ";
  } catch { $("#system-label").textContent = "Пайплайн недоступен"; }
}

async function loadLibrary() {
  const items = await api("/api/characters");
  $("#hero-count").textContent = items.length;
  $("#empty-library").hidden = items.length > 0;
  $("#library").innerHTML = items.map((item) => `
    <article class="hero-item">
      ${item.previewUrl ? `<img src="${item.previewUrl}" alt="${escapeHtml(item.name)}">` : ""}
      <div><h3>${escapeHtml(item.name)}</h3><p>${escapeHtml(item.subject)} · ${escapeHtml(item.status)}</p></div>
    </article>`).join("");
}

function escapeHtml(value) {
  return String(value || "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;" }[c]));
}

planButton.addEventListener("click", async () => {
  if (!form.reportValidity()) return;
  planButton.disabled = true;
  planButton.textContent = "Проверяем…";
  try {
    lastPlan = await api("/api/generations/plan", { method: "POST", body: JSON.stringify(payload()) });
    planResult.hidden = false;
    planResult.textContent = `Замысел валиден. Fingerprint: ${lastPlan.result?.fingerprint || lastPlan.fingerprint || "создан"}.`;
    createButton.disabled = false;
  } catch (error) {
    planResult.hidden = false;
    planResult.textContent = `Ошибка: ${error.message}`;
  } finally {
    planButton.disabled = false;
    planButton.textContent = "Проверить замысел";
  }
});

form.addEventListener("input", () => { createButton.disabled = true; lastPlan = null; });
form.addEventListener("submit", (event) => { event.preventDefault(); if (lastPlan) dialog.showModal(); });
$(".dialog-close").addEventListener("click", () => dialog.close());
$("#confirm-create").addEventListener("click", async () => {
  dialog.close();
  try {
    const job = await api("/api/generations", { method: "POST", body: JSON.stringify({ ...payload(), confirmPaid: true }) });
    showJob(job);
  } catch (error) { alert(`Не удалось запустить: ${error.message}`); }
});

function showJob(job) {
  const drawer = $("#job-drawer");
  drawer.hidden = false;
  $("#job-title").textContent = job.status === "failed" ? "Генерация остановлена" : job.status === "review_required" ? "Канон готов к ревью" : `Создаём: ${job.input.subject}`;
  $("#job-stage").textContent = job.error || job.stage || job.status;
  if (["failed", "review_required"].includes(job.status)) {
    clearTimeout(pollTimer);
    if (job.status === "review_required") loadLibrary();
    return;
  }
  pollTimer = setTimeout(async () => {
    try { showJob(await api(`/api/jobs/${job.id}`)); } catch { pollTimer = setTimeout(() => showJob(job), 5000); }
  }, 4000);
}

document.querySelectorAll(".nav-link").forEach((button) => button.addEventListener("click", () => {
  document.querySelectorAll(".nav-link").forEach((item) => item.classList.toggle("active", item === button));
  document.querySelectorAll(".view").forEach((view) => view.classList.remove("active"));
  $(`#${button.dataset.view}-view`).classList.add("active");
  if (button.dataset.view === "library") loadLibrary();
}));

health();
loadLibrary();
