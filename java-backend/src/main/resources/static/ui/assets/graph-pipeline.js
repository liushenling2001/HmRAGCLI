export const API_BASE = "/api/v1/knowledge-graph";

export const stages = [
  { key: "overview", no: "0", label: "总览", href: "/ui/graph-pipeline.html" },
  { key: "extract", no: "1", label: "抽取任务", href: "/ui/graph-extract.html" },
  { key: "local", no: "2", label: "局部结果", href: "/ui/graph-local.html" },
  { key: "attributes", no: "3", label: "属性治理", href: "/ui/graph-governance.html" },
  { key: "structure", no: "4", label: "结构增强", href: "/ui/graph-structure.html" },
  { key: "fusion", no: "5", label: "实体融合", href: "/ui/graph-fusion.html" },
  { key: "view", no: "6", label: "图谱浏览", href: "/ui/graph.html" },
  { key: "quality", no: "7", label: "质量评估", href: "/ui/graph-quality.html" }
];

export function stageNav(active) {
  return `<nav class="stage-nav" aria-label="图谱流程">${stages.map(stage => `
    <a class="stage-link${stage.key === active ? " active" : ""}" href="${stage.href}">
      <span>${stage.no}</span>
      <strong>${escapeHtml(stage.label)}</strong>
    </a>
  `).join("")}</nav>`;
}

export function buildQuery(params) {
  const query = new URLSearchParams();
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && String(value).trim() !== "") {
      query.set(key, String(value).trim());
    }
  });
  const text = query.toString();
  return text ? `?${text}` : "";
}

export async function request(url, options) {
  const response = await fetch(url, {
    headers: { "Accept": "application/json" },
    ...options
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }
  return response.json();
}

export function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

export function numberValue(value) {
  const number = Number(value ?? 0);
  return Number.isFinite(number) ? number : 0;
}

export function formatTime(value) {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

export function showNotice(el, message, isError) {
  if (!el) return;
  el.textContent = message || "";
  el.className = isError ? "notice error" : "notice";
  el.style.display = message ? "block" : "none";
}

export function statusBadge(status) {
  const normalized = String(status || "").toLowerCase();
  if (normalized === "success") return "success";
  if (normalized === "failed") return "error";
  if (normalized === "running") return "info";
  return "warning";
}

export function summaryValue(summary, key, fallback = "-") {
  return summary && Object.prototype.hasOwnProperty.call(summary, key) ? summary[key] : fallback;
}

export function metric(label, value) {
  return `<div class="metric"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`;
}
