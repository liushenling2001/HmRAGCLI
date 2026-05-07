const { createApp } = Vue;

const api = {
  systemHealth: "/api/v1/system/health",
  dashboard: "/api/v1/operations/dashboard",
  jobs: "/api/v1/operations/jobs",
  failures: "/api/v1/operations/failures",
  cleanupTempFailures: "/api/v1/operations/failures/cleanup-temp-files",
  dataSources: "/api/v1/data-sources",
  domains: "/api/v1/domains",
  refineJobs: "/api/v1/refine-jobs",
  domainMemoryPacks: "/api/v1/domain-memory-packs",
};

createApp({
  data() {
    return {
      activeTab: "overview",
      loading: false,
      loadingHealth: false,
      toast: "",
      dashboard: null,
      systemHealth: null,
      files: [],
      filesTotal: 0,
      jobs: [],
      jobsTotal: 0,
      failures: [],
      failuresTotal: 0,
      filesPage: 1,
      jobsPage: 1,
      failuresPage: 1,
      sourcePage: 1,
      pageSize: 20,
      sourcePageSize: 6,
      sourceView: "active",
      selectedSourceId: "",
      sourceModalOpen: false,
      sourceForm: {
        sourceName: "",
        rootPath: "",
        includePatterns: "*.pdf,*.doc,*.docx,*.xls,*.xlsx,*.txt,*.md",
        excludePatterns: "",
        recursive: true,
      },
      sourceActionState: {},
      knowledgeDomains: [],
      selectedDomainKnowledgeId: "",
      knowledgeResultFilter: "all",
      knowledgeJobs: [],
      knowledgePacks: [],
      knowledgeAgentContextPacks: [],
      loadingKnowledge: false,
      knowledgeActionState: false,
      knowledgeRefineForm: {
        modelProfile: "",
      },
      knowledgeDomainModalOpen: false,
      knowledgeTopicModalOpen: false,
      editingKnowledgeDomainId: "",
      editingKnowledgeTopicId: "",
      knowledgeTopics: [],
      knowledgeDomainForm: {
        name: "",
        autoRefreshEnabled: false,
        autoRefreshMode: "daily",
        autoRefreshTime: "03:00",
        autoRefreshWeekday: "MON",
        assistantQuestion: "",
        assistantAnswer: "",
        assistantHistory: [],
        assistantDraft: {
          goal: "",
          description: "",
          seedQueries: [],
        },
        assistantCurrentDimension: "",
        assistantCoveredDimensions: [],
        assistantNextDimension: "",
        assistantReady: false,
        assistantReason: "",
        assistantStreamingPreview: "",
      },
      knowledgeTopicForm: {
        name: "",
        description: "",
        seedQueries: "",
        priority: 0,
        status: "active",
      },
      selectedKnowledgePackId: "",
      selectedKnowledgePack: null,
      selectedKnowledgeEvidence: [],
      selectedEvidenceContext: null,
      loadingKnowledgeDetail: false,
      intervals: [],
    };
  },
  computed: {
    overview() {
      return this.dashboard?.overview || {};
    },
    dataSources() {
      return this.dashboard?.dataSources || [];
    },
    sortedDataSources() {
      const items = [...this.dataSources];
      return items.sort((a, b) => {
        const aDone = this.isCompletedSource(a);
        const bDone = this.isCompletedSource(b);
        if (aDone !== bDone) return aDone ? 1 : -1;
        const aPending = Number(a.runningFiles || 0) + Number(a.queuedFiles || 0);
        const bPending = Number(b.runningFiles || 0) + Number(b.queuedFiles || 0);
        if (aPending !== bPending) return bPending - aPending;
        return String(a.sourceName || "").localeCompare(String(b.sourceName || ""));
      });
    },
    filteredDataSources() {
      return this.sortedDataSources.filter((source) => this.sourceView === "active" ? !this.isCompletedSource(source) : this.isCompletedSource(source));
    },
    visibleDataSources() {
      const from = (this.sourcePage - 1) * this.sourcePageSize;
      const to = from + this.sourcePageSize;
      return this.filteredDataSources.slice(from, to);
    },
    activeFiles() {
      return this.dashboard?.activeFiles || [];
    },
    recentJobs() {
      return this.dashboard?.recentJobs || [];
    },
    recentFailures() {
      return this.dashboard?.recentFailures || [];
    },
    sourceOptions() {
      return this.dataSources.map((item) => ({ id: item.id, name: item.sourceName }));
    },
    filesTotalPages() {
      return Math.max(1, Math.ceil((this.filesTotal || 0) / this.pageSize));
    },
    jobsTotalPages() {
      return Math.max(1, Math.ceil((this.jobsTotal || 0) / this.pageSize));
    },
    failuresTotalPages() {
      return Math.max(1, Math.ceil((this.failuresTotal || 0) / this.pageSize));
    },
    sourceTotalPages() {
      return Math.max(1, Math.ceil((this.filteredDataSources.length || 0) / this.sourcePageSize));
    },
    selectedKnowledgeDomain() {
      return this.knowledgeDomains.find((item) => item.id === this.selectedDomainKnowledgeId) || null;
    },
    knowledgeAutoStats() {
      const jobs = this.knowledgeJobs || [];
      const packs = this.knowledgePacks || [];
      const autoJobs = jobs.filter((job) => this.normalizeTriggerSource(job?.triggerSource) === "auto");
      const autoPacks = packs.filter((pack) => this.normalizeTriggerSource(pack?.triggerSource) === "auto");
      return {
        jobs: autoJobs.length,
        packs: autoPacks.length,
        latestJobAt: autoJobs[0]?.updatedAt || autoJobs[0]?.createdAt || null,
        latestPackAt: autoPacks[0]?.updatedAt || autoPacks[0]?.createdAt || null,
      };
    },
    autoProblemJobs() {
      return (this.knowledgeJobs || []).filter((job) => {
        const triggerSource = this.normalizeTriggerSource(job?.triggerSource);
        const status = String(job?.status || "").trim().toLowerCase();
        return triggerSource === "auto" && (status === "paused" || status === "failed");
      });
    },
    autoRunTrend() {
      const autoJobs = (this.knowledgeJobs || [])
        .filter((job) => this.normalizeTriggerSource(job?.triggerSource) === "auto")
        .slice(0, 12);
      return autoJobs.map((job) => {
        const relatedPacks = (this.knowledgePacks || []).filter((pack) => pack.refineJobId === job.id);
        const evidenceCount = relatedPacks.reduce((total, pack) => total + (Array.isArray(pack?.evidenceRefs) ? pack.evidenceRefs.length : 0), 0);
        return {
          id: job.id,
          jobType: job.jobType,
          status: job.status,
          modelProfile: job.modelProfile,
          updatedAt: job.updatedAt,
          createdAt: job.createdAt,
          finishedAt: job.finishedAt,
          packCount: relatedPacks.length,
          evidenceCount,
          refinedPackCount: relatedPacks.filter((pack) => this.packRefinementMetadata(pack)?.refined === true).length,
          pausedReason: job.outputSummary?.pauseReason || job.errorMessage || "-",
        };
      });
    },
    filteredKnowledgeJobs() {
      return this.knowledgeJobs.filter((job) => this.matchesKnowledgeResultFilter(job?.triggerSource));
    },
    filteredKnowledgePacks() {
      return this.knowledgePacks.filter((pack) => this.matchesKnowledgeResultFilter(pack?.triggerSource));
    },
    selectedKnowledgePackTitle() {
      return this.selectedKnowledgePack?.title || "";
    },
    selectedKnowledgePackRefinement() {
      return this.selectedKnowledgePack?.sourceSnapshot?.refinement || null;
    },
    selectedKnowledgePackReview() {
      return this.selectedKnowledgePack?.sourceSnapshot?.review || null;
    },
    selectedKnowledgePackEvidenceCount() {
      return Array.isArray(this.selectedKnowledgePack?.evidenceRefs)
        ? this.selectedKnowledgePack.evidenceRefs.length
        : 0;
    },
    autoPackComparison() {
      const autoPacks = (this.knowledgePacks || [])
        .filter((pack) => this.normalizeTriggerSource(pack?.triggerSource) === "auto")
        .sort((a, b) => new Date(b.updatedAt || b.createdAt || 0).getTime() - new Date(a.updatedAt || a.createdAt || 0).getTime());
      if (autoPacks.length < 2) {
        return null;
      }
      const latest = autoPacks[0];
      const previous = autoPacks.find((item) => item.id !== latest.id);
      if (!previous) {
        return null;
      }
      const latestPoints = Array.isArray(latest.keyPoints) ? latest.keyPoints : [];
      const previousPoints = Array.isArray(previous.keyPoints) ? previous.keyPoints : [];
      return {
        latest,
        previous,
        addedPoints: latestPoints.filter((point) => !previousPoints.includes(point)),
        removedPoints: previousPoints.filter((point) => !latestPoints.includes(point)),
      };
    },
    knowledgeAgentContextMode() {
      if ((this.knowledgeAgentContextPacks || []).some((pack) => String(pack?.status || "").toLowerCase() === "accepted")) {
        return "accepted";
      }
      if ((this.knowledgeAgentContextPacks || []).some((pack) => String(pack?.status || "").toLowerCase() === "ready")) {
        return "ready";
      }
      if ((this.knowledgeAgentContextPacks || []).some((pack) => String(pack?.status || "").toLowerCase() === "reference")) {
        return "reference";
      }
      return "empty";
    },
  },
  methods: {
    actionKey(sourceId, action) {
      return `${sourceId}:${action}`;
    },
    isSourceActionRunning(sourceId, action) {
      return !!this.sourceActionState[this.actionKey(sourceId, action)];
    },
    setSourceActionRunning(sourceId, action, running) {
      const key = this.actionKey(sourceId, action);
      if (running) {
        this.sourceActionState = { ...this.sourceActionState, [key]: true };
        return;
      }
      const next = { ...this.sourceActionState };
      delete next[key];
      this.sourceActionState = next;
    },
    async parseError(response) {
      const text = await response.text();
      if (!text) return `${response.status}`;
      try {
        const parsed = JSON.parse(text);
        return parsed.detail || parsed.error || text;
      } catch {
        return text;
      }
    },
    async fetchWithTimeout(url, options = {}, timeoutMs = 15000) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        return await fetch(url, { ...options, signal: controller.signal });
      } finally {
        clearTimeout(timer);
      }
    },
    async getJson(url, timeoutMs = 15000) {
      const response = await this.fetchWithTimeout(url, {}, timeoutMs);
      if (!response.ok) throw new Error(await this.parseError(response));
      return response.json();
    },
    async postJson(url, body = {}, timeoutMs = 15000) {
      const response = await this.fetchWithTimeout(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      }, timeoutMs);
      if (!response.ok) throw new Error(await this.parseError(response));
      return response.json();
    },
    async postNdjson(url, body = {}, onEvent, timeoutMs = 15000) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const response = await fetch(url, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
          signal: controller.signal,
        });
        if (!response.ok) {
          throw new Error(await this.parseError(response));
        }
        if (!response.body) {
          throw new Error("浏览器不支持流式响应");
        }
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const normalized = buffer.replace(/\r\n/g, "\n");
          const lines = normalized.split("\n");
          buffer = lines.pop() || "";
          for (const line of lines) {
            const trimmed = line.trim();
            if (!trimmed) continue;
            onEvent(JSON.parse(trimmed));
          }
        }
        if (buffer.trim()) {
          onEvent(JSON.parse(buffer.trim()));
        }
      } finally {
        clearTimeout(timer);
      }
    },
    async deleteJson(url, timeoutMs = 15000) {
      const response = await this.fetchWithTimeout(url, { method: "DELETE" }, timeoutMs);
      if (!response.ok) throw new Error(await this.parseError(response));
      return true;
    },
    showToast(message) {
      this.toast = message;
      clearTimeout(this._toastTimer);
      this._toastTimer = setTimeout(() => {
        this.toast = "";
      }, 2600);
    },
    statusClass(status) {
      const lowered = String(status || "").toLowerCase();
      if (["success", "fully_ready"].includes(lowered)) return "ok";
      if (["needs_approval", "paused"].includes(lowered)) return "warn";
      if (["cancelled", "cancelling"].includes(lowered)) return "info";
      if (["failed", "build_failed"].includes(lowered)) return "fail";
      if (["running", "queued", "partial_failed", "processing"].includes(lowered)) return "warn";
      return "info";
    },
    formatDate(value) {
      if (!value) return "-";
      try {
        return new Date(value).toLocaleString();
      } catch {
        return value;
      }
    },
    formatJsonBrief(value) {
      if (!value) return "-";
      try {
        return JSON.stringify(value, null, 2);
      } catch {
        return String(value);
      }
    },
    shorten(text, max = 180) {
      const value = String(text || "").trim();
      if (!value) return "-";
      return value.length > max ? `${value.slice(0, max)}...` : value;
    },
    normalizeTriggerSource(value) {
      const lowered = String(value || "").trim().toLowerCase();
      return lowered || "unknown";
    },
    matchesKnowledgeResultFilter(triggerSource) {
      if (this.knowledgeResultFilter === "all") {
        return true;
      }
      return this.normalizeTriggerSource(triggerSource) === this.knowledgeResultFilter;
    },
    triggerSourceLabel(value) {
      const normalized = this.normalizeTriggerSource(value);
      if (normalized === "auto") return "自动";
      if (normalized === "user") return "人工";
      return normalized;
    },
    triggerSourceClass(value) {
      const normalized = this.normalizeTriggerSource(value);
      if (normalized === "auto") return "ok";
      if (normalized === "user") return "info";
      return "warn";
    },
    packRefinementMetadata(pack) {
      return pack?.sourceSnapshot?.refinement || null;
    },
    packRefinementState(pack) {
      const metadata = this.packRefinementMetadata(pack);
      if (!metadata) return "未记录";
      if (metadata.refined === true) return "LLM已精炼";
      const reason = String(metadata.reason || "").trim();
      if (reason) return `暂停: ${reason}`;
      return "草稿";
    },
    packRefinementClass(pack) {
      const metadata = this.packRefinementMetadata(pack);
      if (metadata?.refined === true) return "ok";
      if (metadata?.reason) return "warn";
      return "info";
    },
    packRefinementModel(pack) {
      const metadata = this.packRefinementMetadata(pack);
      const provider = String(metadata?.llmProvider || "").trim();
      const model = String(metadata?.llmModel || pack?.modelProfile || "").trim();
      if (!provider && !model) return "-";
      if (provider && model) return `${provider} / ${model}`;
      return provider || model;
    },
    packReview(pack) {
      return pack?.sourceSnapshot?.review || null;
    },
    packReviewLabel(pack) {
      const status = String(this.packReview(pack)?.status || pack?.status || "").trim().toLowerCase();
      if (status === "accepted") return "确认采用";
      if (status === "reference") return "仅供参考";
      return "待确认";
    },
    packReviewClass(pack) {
      const status = String(this.packReview(pack)?.status || pack?.status || "").trim().toLowerCase();
      if (status === "accepted") return "ok";
      if (status === "reference") return "warn";
      return "info";
    },
    parseAutoRefreshSchedule(cron) {
      const raw = String(cron || "").trim();
      if (!raw) {
        return { mode: "daily", time: "03:00", weekday: "MON" };
      }
      const parts = raw.split(/\s+/);
      if (parts.length === 6) {
        const minute = parts[1]?.padStart(2, "0") || "00";
        const hour = parts[2]?.padStart(2, "0") || "03";
        const dayOfWeek = parts[5];
        return {
          mode: dayOfWeek && dayOfWeek !== "*" ? "weekly" : "daily",
          time: `${hour}:${minute}`,
          weekday: dayOfWeek && dayOfWeek !== "*" ? dayOfWeek.toUpperCase() : "MON",
        };
      }
      return { mode: "daily", time: "03:00", weekday: "MON" };
    },
    buildAutoRefreshCron() {
      if (!this.knowledgeDomainForm.autoRefreshEnabled) {
        return null;
      }
      const time = String(this.knowledgeDomainForm.autoRefreshTime || "03:00");
      const [hourRaw, minuteRaw] = time.split(":");
      const hour = Number.isFinite(Number(hourRaw)) ? Math.max(0, Math.min(23, Number(hourRaw))) : 3;
      const minute = Number.isFinite(Number(minuteRaw)) ? Math.max(0, Math.min(59, Number(minuteRaw))) : 0;
      if (this.knowledgeDomainForm.autoRefreshMode === "weekly") {
        const weekday = String(this.knowledgeDomainForm.autoRefreshWeekday || "MON").toUpperCase();
        return `0 ${minute} ${hour} * * ${weekday}`;
      }
      return `0 ${minute} ${hour} * * *`;
    },
    async runDomainSetupAssistant() {
      const domainName = String(this.knowledgeDomainForm.name || "").trim();
      if (!domainName) {
        this.showToast("请先填写领域名称");
        return;
      }
      this.knowledgeActionState = true;
      this.knowledgeDomainForm.assistantReason = "正在连接领域分析模型...";
      this.knowledgeDomainForm.assistantStreamingPreview = "";
      try {
        await this.postNdjson(`${api.domains}/setup-assistant/stream`, {
          name: domainName,
          history: this.knowledgeDomainForm.assistantHistory,
        }, (event) => this.handleDomainSetupStreamEvent(event), 130000);
      } catch (error) {
        const message = error?.name === "AbortError"
          ? "AI引导等待超时：前端等待已结束，请检查领域分析模型响应速度或稍后重试"
          : (error?.message || "启动AI引导失败");
        this.knowledgeDomainForm.assistantQuestion = "";
        this.knowledgeDomainForm.assistantReason = message;
        this.showToast(message);
      } finally {
        this.knowledgeActionState = false;
      }
    },
    async sendDomainSetupAnswer() {
      const answer = String(this.knowledgeDomainForm.assistantAnswer || "").trim();
      if (!answer) {
        this.showToast("请先输入回答");
        return;
      }
      const question = String(this.knowledgeDomainForm.assistantQuestion || "").trim();
      const nextHistory = [...this.knowledgeDomainForm.assistantHistory];
      if (question) {
        nextHistory.push({ role: "assistant", content: question });
      }
      nextHistory.push({ role: "user", content: answer });
      this.knowledgeDomainForm.assistantHistory = nextHistory;
      this.knowledgeDomainForm.assistantAnswer = "";
      this.knowledgeActionState = true;
      this.knowledgeDomainForm.assistantReason = "正在把回答发送给领域分析模型...";
      this.knowledgeDomainForm.assistantStreamingPreview = "";
      try {
        await this.postNdjson(`${api.domains}/setup-assistant/stream`, {
          name: String(this.knowledgeDomainForm.name || "").trim(),
          history: nextHistory,
        }, (event) => this.handleDomainSetupStreamEvent(event), 130000);
      } catch (error) {
        const message = error?.name === "AbortError"
          ? "AI引导等待超时：前端等待已结束，请检查领域分析模型响应速度或稍后重试"
          : (error?.message || "提交AI回答失败");
        this.knowledgeDomainForm.assistantReason = message;
        this.showToast(message);
      } finally {
        this.knowledgeActionState = false;
      }
    },
    handleDomainSetupStreamEvent(event) {
      const type = String(event?.type || "").trim();
      const payload = event?.payload || {};
      if (type === "status") {
        this.knowledgeDomainForm.assistantReason = payload.message || "模型处理中...";
        return;
      }
      if (type === "delta") {
        this.knowledgeDomainForm.assistantStreamingPreview = String(payload.preview || payload.content || "").trim();
        this.knowledgeDomainForm.assistantReason = "模型正在生成配置问题...";
        return;
      }
      if (type === "result") {
        this.knowledgeDomainForm.assistantQuestion = payload.question || "";
        this.knowledgeDomainForm.assistantDraft = {
          goal: payload.goal || "",
          description: payload.description || "",
          seedQueries: payload.seedQueries || [],
        };
        this.knowledgeDomainForm.assistantCurrentDimension = payload.currentDimension || "";
        this.knowledgeDomainForm.assistantCoveredDimensions = payload.coveredDimensions || [];
        this.knowledgeDomainForm.assistantNextDimension = payload.nextDimension || "";
        this.knowledgeDomainForm.assistantReady = !!payload.ready;
        this.knowledgeDomainForm.assistantReason = payload.reason || "领域引导已完成";
        this.knowledgeDomainForm.assistantStreamingPreview = "";
        return;
      }
      if (type === "error") {
        this.knowledgeDomainForm.assistantReason = payload.message || "领域引导失败";
        throw new Error(this.knowledgeDomainForm.assistantReason);
      }
    },
    summaryDiff(current, previous) {
      const currentText = String(current || "").trim();
      const previousText = String(previous || "").trim();
      if (!currentText && !previousText) {
        return "两次结果都没有摘要。";
      }
      if (!previousText) {
        return `新增摘要：${this.shorten(currentText, 260)}`;
      }
      if (!currentText) {
        return "最新结果没有摘要。";
      }
      if (currentText === previousText) {
        return "摘要没有变化。";
      }
      return `最新：${this.shorten(currentText, 140)}\n上一版：${this.shorten(previousText, 140)}`;
    },
    async reviewKnowledgePack(pack, status) {
      const actionLabel = status === "accepted" ? "确认采用" : "标记为仅供参考";
      const ok = window.confirm(`确认将知识包「${pack?.title || "-"}」${actionLabel}吗？`);
      if (!ok) return;
      this.knowledgeActionState = true;
      try {
        const updated = await this.postJson(`${api.domainMemoryPacks}/${pack.id}/review`, {
          status,
          note: status === "accepted" ? "ops-ui manual acceptance" : "ops-ui manual reference mark",
          reviewedBy: "ops-ui",
        }, 15000);
        if (this.selectedKnowledgePackId === updated.id) {
          this.selectedKnowledgePack = updated;
        }
        this.showToast(`知识包已${actionLabel}`);
        await this.loadKnowledge();
      } catch (error) {
        this.showToast(error?.message || "更新知识包审核状态失败");
      } finally {
        this.knowledgeActionState = false;
      }
    },
    parseLines(value) {
      return String(value || "")
        .split(/\r?\n|,/)
        .map((item) => item.trim())
        .filter(Boolean);
    },
    pageWindow(current, total) {
      const safeCurrent = Math.max(1, current || 1);
      const safeTotal = Math.max(1, total || 1);
      const start = Math.max(1, safeCurrent - 2);
      const end = Math.min(safeTotal, start + 4);
      const adjustedStart = Math.max(1, end - 4);
      const pages = [];
      for (let i = adjustedStart; i <= end; i += 1) pages.push(i);
      return pages;
    },
    stageLabel(stage) {
      const map = {
        probe: "探测",
        parse: "解析",
        extract: "抽取",
        vector: "向量",
        completed: "完成",
        needs_approval: "待批准",
      };
      return map[String(stage || "").toLowerCase()] || stage || "-";
    },
    stagePercent(stage) {
      const total = Number(stage?.total || stage?.totalUnits || 0);
      const completed = Number(stage?.completed || stage?.completedUnits || 0);
      if (!total) return 0;
      return Math.min(100, Math.round((completed * 100) / total));
    },
    progressClassFromStatus(status) {
      const lowered = String(status || "").toLowerCase();
      if (["running", "processing"].includes(lowered)) return "processing";
      if (["success", "fully_ready"].includes(lowered)) return "success";
      if (["needs_approval"].includes(lowered)) return "fail";
      if (["cancelled", "cancelling"].includes(lowered)) return "pending";
      if (["failed", "build_failed"].includes(lowered)) return "fail";
      return "pending";
    },
    stageAggregateStatus(stage) {
      if (!stage) return "pending";
      if (Number(stage.runningFiles || 0) > 0) return "running";
      if (Number(stage.failedFiles || 0) > 0) return "failed";
      const pending = Number(stage.pendingFiles || 0);
      const success = Number(stage.successFiles || 0);
      const skipped = Number(stage.skippedFiles || 0);
      if (pending === 0 && (success + skipped) > 0) return "success";
      return "pending";
    },
    sourceStageProgressClass(source, stageName) {
      return this.progressClassFromStatus(this.stageAggregateStatus(this.sourceStage(source, stageName)));
    },
    fileTotalProgressClass(file) {
      return this.progressClassFromStatus(file?.lifecycleStatus);
    },
    jobProgressClass(job) {
      return this.progressClassFromStatus(job?.status);
    },
    sourceStage(source, stageName) {
      return (source.stages || []).find((stage) => stage.stage === stageName) || null;
    },
    fileStage(file, stageName) {
      return (file.stageTasks || []).find((stage) => stage.stage === stageName) || null;
    },
    stageSummary(stage) {
      if (!stage) return "-";
      if (stage.status === "failed") return stage.errorMessage || "失败";
      return `${stage.completed}/${stage.total}`;
    },
    stageStatusText(stage) {
      if (!stage) return "未创建";
      const percent = this.stagePercent(stage);
      const heartbeat = stage.heartbeatAt ? this.formatDate(stage.heartbeatAt) : "";
      switch (stage.status) {
        case "running":
          return `执行中 ${stage.completed}/${stage.total} (${percent}%)${heartbeat ? ` · 心跳 ${heartbeat}` : ""}`;
        case "success":
          return `已完成 ${stage.completed}/${stage.total}${stage.finishedAt ? ` · ${this.formatDate(stage.finishedAt)}` : ""}`;
        case "failed":
          return stage.errorMessage || "失败";
        case "needs_approval":
          return stage.errorMessage || "等待人工批准";
        case "cancelling":
          return "正在停止";
        case "cancelled":
          return stage.errorMessage || "已停止";
        case "blocked":
          return "等待上一步完成";
        case "pending":
          return "已排队，等待执行";
        case "skipped":
          return "已跳过";
        default:
          return stage.status || "-";
      }
    },
    isStaleStage(stage) {
      if (!stage || stage.status !== "running" || !stage.heartbeatAt) return false;
      return Date.now() - new Date(stage.heartbeatAt).getTime() > 90000;
    },
    sourceStageSummary(stage) {
      if (!stage) return "-";
      return `成功 ${stage.successFiles} / 运行 ${stage.runningFiles} / 失败 ${stage.failedFiles}`;
    },
    fileCurrentStageDetail(file) {
      const stage = this.fileStage(file, file.currentStage);
      if (!stage) return "等待处理";
      const text = this.stageStatusText(stage);
      return this.isStaleStage(stage) ? `${text} · 可能卡住` : text;
    },
    isCompletedSource(source) {
      const total = Number(source.totalFiles || 0);
      if (!total) return false;
      return Number(source.runningFiles || 0) === 0 && Number(source.queuedFiles || 0) === 0 && (Number(source.readyFiles || 0) + Number(source.failedFiles || 0) >= total);
    },
    async loadDashboard() {
      this.dashboard = await this.getJson(api.dashboard);
      if (!this.selectedSourceId && this.dataSources.length) {
        this.selectedSourceId = this.dataSources[0].id;
      }
      if (this.sourcePage > this.sourceTotalPages) {
        this.sourcePage = this.sourceTotalPages;
      }
    },
    async loadSystemHealth(silent = false) {
      this.loadingHealth = true;
      try {
        this.systemHealth = await this.getJson(api.systemHealth);
        if (!silent) {
          this.showToast("系统状态已刷新");
        }
      } finally {
        this.loadingHealth = false;
      }
    },
    async loadKnowledge() {
      this.loadingKnowledge = true;
      try {
        this.knowledgeDomains = await this.getJson(api.domains);
        if (!this.selectedDomainKnowledgeId && this.knowledgeDomains.length) {
          this.selectedDomainKnowledgeId = this.knowledgeDomains[0].id;
        }
        this.knowledgeTopics = this.selectedDomainKnowledgeId
          ? await this.getJson(`${api.domains}/${this.selectedDomainKnowledgeId}/topics`)
          : [];
        const params = new URLSearchParams();
        if (this.selectedDomainKnowledgeId) {
          params.set("domainId", this.selectedDomainKnowledgeId);
        }
        if (this.knowledgeResultFilter !== "all") {
          params.set("triggerSource", this.knowledgeResultFilter);
        }
        const query = params.toString() ? `?${params.toString()}` : "";
        this.knowledgeJobs = await this.getJson(`${api.refineJobs}${query}`);
        this.knowledgePacks = await this.getJson(`${api.domainMemoryPacks}${query}`);
        const agentParams = new URLSearchParams();
        if (this.selectedDomainKnowledgeId) {
          agentParams.set("domainId", this.selectedDomainKnowledgeId);
        }
        agentParams.set("limit", "5");
        this.knowledgeAgentContextPacks = await this.getJson(`${api.domainMemoryPacks}/agent-context?${agentParams.toString()}`);
      } finally {
        this.loadingKnowledge = false;
      }
    },
    async changeKnowledgeResultFilter() {
      this.selectedKnowledgePackId = "";
      this.selectedKnowledgePack = null;
      this.selectedKnowledgeEvidence = [];
      this.selectedEvidenceContext = null;
      await this.loadKnowledge();
    },
    openKnowledgeDomainModal(domain = null) {
      this.editingKnowledgeDomainId = domain?.id || "";
      const parsedSchedule = this.parseAutoRefreshSchedule(domain?.autoRefreshCron || "");
      this.knowledgeDomainForm = {
        name: domain?.name || "",
        autoRefreshEnabled: !!domain?.autoRefreshEnabled,
        autoRefreshMode: parsedSchedule.mode,
        autoRefreshTime: parsedSchedule.time,
        autoRefreshWeekday: parsedSchedule.weekday,
        assistantQuestion: domain?.description
          ? "已载入当前领域配置，你可以继续补充或直接保存。"
          : "请先点击“开始AI引导”，系统会逐步提问。",
        assistantAnswer: "",
        assistantHistory: [],
        assistantDraft: {
          goal: domain?.goal || "",
          description: domain?.description || "",
          seedQueries: domain?.seedQueries || [],
        },
        assistantCurrentDimension: domain?.metadata?.setupAssistantCurrentDimension || "",
        assistantCoveredDimensions: domain?.metadata?.setupAssistantCoveredDimensions || [],
        assistantNextDimension: domain?.metadata?.setupAssistantNextDimension || "",
        assistantReady: !!(domain?.goal || domain?.description || (domain?.seedQueries || []).length),
        assistantReason: domain?.metadata?.setupAssistantReason || "",
        assistantStreamingPreview: "",
      };
      this.knowledgeDomainModalOpen = true;
    },
    closeKnowledgeDomainModal() {
      this.knowledgeDomainModalOpen = false;
      this.editingKnowledgeDomainId = "";
      this.knowledgeDomainForm = {
        name: "",
        autoRefreshEnabled: false,
        autoRefreshMode: "daily",
        autoRefreshTime: "03:00",
        autoRefreshWeekday: "MON",
        assistantQuestion: "",
        assistantAnswer: "",
        assistantHistory: [],
        assistantDraft: {
          goal: "",
          description: "",
          seedQueries: [],
        },
        assistantCurrentDimension: "",
        assistantCoveredDimensions: [],
        assistantNextDimension: "",
        assistantReady: false,
        assistantReason: "",
        assistantStreamingPreview: "",
      };
    },
    openKnowledgeTopicModal(topic = null) {
      if (!this.selectedDomainKnowledgeId) {
        this.showToast("请先选择领域");
        return;
      }
      this.editingKnowledgeTopicId = topic?.id || "";
      this.knowledgeTopicForm = {
        name: topic?.name || "",
        description: topic?.description || "",
        seedQueries: (topic?.seedQueries || []).join("\n"),
        priority: Number(topic?.priority || 0),
        status: topic?.status || "active",
      };
      this.knowledgeTopicModalOpen = true;
    },
    closeKnowledgeTopicModal() {
      this.knowledgeTopicModalOpen = false;
      this.editingKnowledgeTopicId = "";
      this.knowledgeTopicForm = {
        name: "",
        description: "",
        seedQueries: "",
        priority: 0,
        status: "active",
      };
    },
    async saveKnowledgeDomain() {
      const draft = this.knowledgeDomainForm.assistantDraft || { goal: "", description: "", seedQueries: [] };
      const payload = {
        name: this.knowledgeDomainForm.name.trim(),
        description: String(draft.description || "").trim() || null,
        goal: String(draft.goal || "").trim() || null,
        scopeRules: {},
        seedQueries: Array.isArray(draft.seedQueries) ? draft.seedQueries : [],
        includeDataSources: [],
        excludeDataSources: [],
        priority: 0,
        autoRefreshEnabled: !!this.knowledgeDomainForm.autoRefreshEnabled,
        autoRefreshCron: this.buildAutoRefreshCron(),
        activeModelProfile: null,
        status: "draft",
        createdBy: "ops-ui",
        metadata: {
          setupMode: "assistant",
          setupAssistantReason: this.knowledgeDomainForm.assistantReason || "",
          setupAssistantCurrentDimension: this.knowledgeDomainForm.assistantCurrentDimension || "",
          setupAssistantCoveredDimensions: this.knowledgeDomainForm.assistantCoveredDimensions || [],
          setupAssistantNextDimension: this.knowledgeDomainForm.assistantNextDimension || "",
          setupHistory: this.knowledgeDomainForm.assistantHistory,
        },
      };
      try {
        if (this.editingKnowledgeDomainId) {
          await this.fetchWithTimeout(`${api.domains}/${this.editingKnowledgeDomainId}`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
          }, 15000).then(async (response) => {
            if (!response.ok) throw new Error(await this.parseError(response));
            return response.json();
          });
          this.showToast("领域已更新");
        } else {
          await this.postJson(api.domains, payload, 15000);
          this.showToast("领域已创建");
        }
        this.closeKnowledgeDomainModal();
        await this.loadKnowledge();
      } catch (error) {
        this.showToast(error?.message || "保存领域失败");
      }
    },
    async deleteKnowledgeDomain(domain) {
      const ok = window.confirm(`确认删除领域「${domain?.name || "-"}」吗？会级联删除其专题、精炼任务和知识包。`);
      if (!ok) return;
      try {
        const response = await this.fetchWithTimeout(`${api.domains}/${domain.id}`, { method: "DELETE" }, 15000);
        if (!response.ok) throw new Error(await this.parseError(response));
        if (this.selectedDomainKnowledgeId === domain.id) {
          this.selectedDomainKnowledgeId = "";
          this.selectedKnowledgePackId = "";
          this.selectedKnowledgePack = null;
          this.selectedKnowledgeEvidence = [];
          this.selectedEvidenceContext = null;
        }
        this.showToast("领域已删除");
        await this.loadKnowledge();
      } catch (error) {
        this.showToast(error?.message || "删除领域失败");
      }
    },
    async saveKnowledgeTopic() {
      if (!this.selectedDomainKnowledgeId) {
        this.showToast("请先选择领域");
        return;
      }
      const payload = {
        parentTopicId: null,
        name: this.knowledgeTopicForm.name.trim(),
        description: this.knowledgeTopicForm.description.trim() || null,
        scopeRules: {},
        seedQueries: this.parseLines(this.knowledgeTopicForm.seedQueries),
        priority: Number(this.knowledgeTopicForm.priority || 0),
        status: this.knowledgeTopicForm.status || "active",
        metadata: {},
      };
      try {
        if (this.editingKnowledgeTopicId) {
          await this.fetchWithTimeout(`/api/v1/topics/${this.editingKnowledgeTopicId}`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
          }, 15000).then(async (response) => {
            if (!response.ok) throw new Error(await this.parseError(response));
            return response.json();
          });
          this.showToast("专题已更新");
        } else {
          await this.postJson(`${api.domains}/${this.selectedDomainKnowledgeId}/topics`, payload, 15000);
          this.showToast("专题已创建");
        }
        this.closeKnowledgeTopicModal();
        await this.loadKnowledge();
      } catch (error) {
        this.showToast(error?.message || "保存专题失败");
      }
    },
    async deleteKnowledgeTopic(topic) {
      const ok = window.confirm(`确认删除专题「${topic?.name || "-"}」吗？相关专题级精炼任务和知识包将失去该专题关联。`);
      if (!ok) return;
      try {
        const response = await this.fetchWithTimeout(`/api/v1/topics/${topic.id}`, { method: "DELETE" }, 15000);
        if (!response.ok) throw new Error(await this.parseError(response));
        this.showToast("专题已删除");
        await this.loadKnowledge();
      } catch (error) {
        this.showToast(error?.message || "删除专题失败");
      }
    },
    async startKnowledgeRefine() {
      if (!this.selectedDomainKnowledgeId) {
        this.showToast("请先选择领域");
        return;
      }
      const domain = this.selectedKnowledgeDomain;
      const ok = window.confirm(`确认立即精炼领域「${domain?.name || this.selectedDomainKnowledgeId}」吗？`);
      if (!ok) return;
      this.knowledgeActionState = true;
      try {
        await this.postJson(`${api.domains}/${this.selectedDomainKnowledgeId}/refine`, {
          modelProfile: (this.knowledgeRefineForm.modelProfile || "").trim() || null,
          triggerSource: "user",
        }, 15000);
        this.showToast("领域精炼任务已入队");
        await this.loadKnowledge();
      } catch (error) {
        this.showToast(error?.message || "提交领域精炼任务失败");
      } finally {
        this.knowledgeActionState = false;
      }
    },
    async startTopicRefine(topic) {
      const ok = window.confirm(`确认立即精炼专题「${topic?.name || "-"}」吗？`);
      if (!ok) return;
      this.knowledgeActionState = true;
      try {
        await this.postJson(`/api/v1/topics/${topic.id}/refine`, {
          modelProfile: (this.knowledgeRefineForm.modelProfile || "").trim() || null,
          triggerSource: "user",
        }, 15000);
        this.showToast("专题精炼任务已入队");
        await this.loadKnowledge();
      } catch (error) {
        this.showToast(error?.message || "提交专题精炼任务失败");
      } finally {
        this.knowledgeActionState = false;
      }
    },
    async rerunPackRefine(pack) {
      const targetName = pack.title || pack.id;
      const ok = window.confirm(`确认按当前模型配置重新精炼知识包「${targetName}」吗？`);
      if (!ok) return;
      this.knowledgeActionState = true;
      try {
        const payload = {
          modelProfile: (this.knowledgeRefineForm.modelProfile || "").trim() || null,
          triggerSource: "user",
        };
        if (pack.topicId) {
          await this.postJson(`/api/v1/topics/${pack.topicId}/refine`, payload, 15000);
        } else {
          await this.postJson(`/api/v1/domains/${pack.domainId}/refine`, payload, 15000);
        }
        this.showToast("重新精炼任务已入队");
        await this.loadKnowledge();
      } catch (error) {
        this.showToast(error?.message || "重新精炼失败");
      } finally {
        this.knowledgeActionState = false;
      }
    },
    async changeKnowledgeDomain() {
      this.selectedKnowledgePackId = "";
      this.selectedKnowledgePack = null;
      this.selectedKnowledgeEvidence = [];
      this.selectedEvidenceContext = null;
      await this.loadKnowledge();
    },
    async resumeRefineJob(job) {
      const ok = window.confirm(`确认继续精炼任务 ${job.id} 吗？当前暂停原因：${job.errorMessage || "未知"}`);
      if (!ok) return;
      try {
        await this.postJson(`${api.refineJobs}/${job.id}/resume`, {}, 15000);
        this.showToast("精炼任务已重新入队");
        await this.loadKnowledge();
      } catch (error) {
        this.showToast(error?.message || "继续精炼任务失败");
      }
    },
    async openKnowledgePack(pack) {
      this.selectedKnowledgePackId = pack.id;
      this.selectedKnowledgePack = pack;
      this.selectedEvidenceContext = null;
      this.loadingKnowledgeDetail = true;
      try {
        this.selectedKnowledgeEvidence = await this.getJson(`${api.domainMemoryPacks}/${pack.id}/evidence`);
      } finally {
        this.loadingKnowledgeDetail = false;
      }
    },
    async loadEvidenceContext(evidence) {
      if (!this.selectedKnowledgePackId || !evidence?.evidenceRef) return;
      this.loadingKnowledgeDetail = true;
      try {
        this.selectedEvidenceContext = await this.getJson(
          `${api.domainMemoryPacks}/${this.selectedKnowledgePackId}/context?evidenceRef=${encodeURIComponent(evidence.evidenceRef)}&window=1`
        );
      } finally {
        this.loadingKnowledgeDetail = false;
      }
    },
    async loadFiles() {
      if (!this.selectedSourceId) {
        this.files = [];
        this.filesTotal = 0;
        return;
      }
      const params = new URLSearchParams({ page: String(this.filesPage), pageSize: String(this.pageSize) });
      const data = await this.getJson(`${api.dataSources}/${this.selectedSourceId}/files?${params.toString()}`);
      this.files = data.items || [];
      this.filesTotal = data.total || 0;
    },
    async loadJobs() {
      const params = new URLSearchParams({ page: String(this.jobsPage), pageSize: String(this.pageSize) });
      const data = await this.getJson(`${api.jobs}?${params.toString()}`);
      this.jobs = data.items || [];
      this.jobsTotal = data.total || 0;
    },
    async loadFailures() {
      const params = new URLSearchParams({ page: String(this.failuresPage), pageSize: String(this.pageSize) });
      const data = await this.getJson(`${api.failures}?${params.toString()}`);
      this.failures = data.items || [];
      this.failuresTotal = data.total || 0;
    },
    async cleanupTempFailures() {
      const ok = window.confirm("确认清理失败列表中的 Office 临时文件（~$）吗？");
      if (!ok) return;
      const result = await this.postJson(api.cleanupTempFailures, {});
      this.showToast(`已清理临时失败记录 ${result.cleanedFiles || 0} 条`);
      this.failuresPage = 1;
      await this.refreshData();
    },
    async refreshData() {
      await Promise.all([this.loadDashboard(), this.loadJobs(), this.loadFailures()]);
      await this.loadFiles();
    },
    async refreshAll() {
      await Promise.all([this.refreshData(), this.loadSystemHealth(true), this.loadKnowledge()]);
    },
    async changeFilesPage(page) {
      const next = Math.min(this.filesTotalPages, Math.max(1, page));
      if (next === this.filesPage) return;
      this.filesPage = next;
      await this.loadFiles();
    },
    async changeJobsPage(page) {
      const next = Math.min(this.jobsTotalPages, Math.max(1, page));
      if (next === this.jobsPage) return;
      this.jobsPage = next;
      await this.loadJobs();
    },
    async changeFailuresPage(page) {
      const next = Math.min(this.failuresTotalPages, Math.max(1, page));
      if (next === this.failuresPage) return;
      this.failuresPage = next;
      await this.loadFailures();
    },
    changeSourcePage(page) {
      this.sourcePage = Math.min(this.sourceTotalPages, Math.max(1, page));
    },
    switchSourceView(view) {
      this.sourceView = view;
      this.sourcePage = 1;
    },
    async triggerSourceAction(sourceId, action) {
      this.setSourceActionRunning(sourceId, action, true);
      try {
        if (action === "scan") {
          this.showToast("扫描请求已提交，正在进入队列...");
          await this.postJson(`${api.dataSources}/${sourceId}/scan`, { forceRescan: false });
          this.showToast("扫描已进入队列");
        } else if (action === "retryFailed") {
          this.showToast("重跑失败请求已提交，正在进入队列...");
          await this.postJson(`${api.dataSources}/${sourceId}/ingest`, { mode: "retry_failed", reprocessFailed: true });
          this.showToast("失败文件重跑已进入队列");
        } else {
          this.showToast("入库请求已提交，正在进入队列...");
          await this.postJson(`${api.dataSources}/${sourceId}/ingest`, { mode: "incremental", reprocessFailed: false });
          this.showToast("入库已进入队列");
        }
        this.activeTab = "jobs";
        this.jobsPage = 1;
        await this.loadJobs();
        this.refreshData().catch(() => {});
      } catch (error) {
        this.showToast(error?.message || "任务提交失败");
      } finally {
        this.setSourceActionRunning(sourceId, action, false);
      }
    },
    async resetSourceIndex(source) {
      const ok = window.confirm(`确认清理数据源「${source.sourceName}」的已建索引吗？这会删除文档、分块、抽取和向量结果。`);
      if (!ok) return;
      this.setSourceActionRunning(source.id, "reset", true);
      this.showToast(`已提交清理索引：${source.sourceName}`);
      let result;
      try {
        result = await this.postJson(`${api.dataSources}/${source.id}/index/reset`, {}, 60000);
      } catch (error) {
        const message = error?.name === "AbortError"
          ? "清理索引超时，请检查后端日志或索引目录是否被占用"
          : (error?.message || "清理索引失败");
        this.showToast(message);
        this.setSourceActionRunning(source.id, "reset", false);
        return;
      }
      const deletedDirCount = Array.isArray(result.deletedIndexDirs) ? result.deletedIndexDirs.length : 0;
      const failedDirCount = result.indexDirErrors ? Object.keys(result.indexDirErrors).length : 0;
      this.showToast(`清理完成：文档 ${result.deletedDocuments}，块 ${result.deletedChunks}，知识单元 ${result.deletedKnowledgeUnits}，目录 ${deletedDirCount}${failedDirCount ? `，目录失败 ${failedDirCount}` : ""}`);
      this.setSourceActionRunning(source.id, "reset", false);
      await this.refreshData();
    },
    async cancelSourceJobs(source) {
      const ok = window.confirm(`确认停止数据源「${source.sourceName}」的运行任务吗？`);
      if (!ok) return;
      this.setSourceActionRunning(source.id, "cancel", true);
      this.showToast(`已提交停止任务：${source.sourceName}`);
      try {
        await this.postJson(`${api.dataSources}/${source.id}/cancel`, {}, 30000);
        this.showToast("停止请求已进入队列");
        this.activeTab = "jobs";
        this.jobsPage = 1;
        await this.loadJobs();
        this.refreshData().catch(() => {});
      } catch (error) {
        this.showToast(error?.message || "停止任务失败");
      } finally {
        this.setSourceActionRunning(source.id, "cancel", false);
      }
    },
    async approveDegradedProcessing(source) {
      const ok = window.confirm(`确认批准数据源「${source.sourceName}」在本次任务中允许降级继续执行吗？`);
      if (!ok) return;
      this.setSourceActionRunning(source.id, "approve", true);
      this.showToast(`已提交人工批准：${source.sourceName}`);
      try {
        await this.postJson(`${api.dataSources}/${source.id}/approve-degraded-processing`, {}, 30000);
        this.showToast("已批准降级继续执行");
        this.activeTab = "jobs";
        this.jobsPage = 1;
        await this.loadJobs();
        this.refreshData().catch(() => {});
      } catch (error) {
        this.showToast(error?.message || "批准降级失败");
      } finally {
        this.setSourceActionRunning(source.id, "approve", false);
      }
    },
    async removeDataSource(source) {
      const ok = window.confirm(`确认删除数据源「${source.sourceName}」吗？会同时删除该数据源的文件记录、任务记录和索引数据。`);
      if (!ok) return;
      this.setSourceActionRunning(source.id, "delete", true);
      this.showToast(`已提交删除数据源：${source.sourceName}`);
      try {
        await this.deleteJson(`${api.dataSources}/${source.id}`, 60000);
      } catch (error) {
        const message = error?.name === "AbortError"
          ? "删除数据源超时，请检查后端日志或重试"
          : (error?.message || "删除数据源失败");
        this.showToast(message);
        this.setSourceActionRunning(source.id, "delete", false);
        return;
      }
      if (this.selectedSourceId === source.id) {
        this.selectedSourceId = "";
        this.files = [];
        this.filesTotal = 0;
      }
      this.showToast(`已删除数据源：${source.sourceName}`);
      this.setSourceActionRunning(source.id, "delete", false);
      await this.refreshData();
    },
    openSourceModal() {
      this.sourceModalOpen = true;
    },
    closeSourceModal() {
      this.sourceModalOpen = false;
      this.sourceForm = {
        sourceName: "",
        rootPath: "",
        includePatterns: "*.pdf,*.doc,*.docx,*.xls,*.xlsx,*.txt,*.md",
        excludePatterns: "",
        recursive: true,
      };
    },
    async createSource() {
      await this.postJson(api.dataSources, {
        sourceName: this.sourceForm.sourceName.trim(),
        sourceType: "local_dir",
        rootPath: this.sourceForm.rootPath.trim(),
        includePatterns: this.sourceForm.includePatterns.split(",").map((x) => x.trim()).filter(Boolean),
        excludePatterns: this.sourceForm.excludePatterns.split(",").map((x) => x.trim()).filter(Boolean),
        recursive: this.sourceForm.recursive,
        metadata: {},
      });
      this.closeSourceModal();
      this.showToast("数据源创建成功");
      await this.refreshData();
    },
    startPolling() {
      this.intervals.push(setInterval(() => this.refreshAll().catch(() => {}), 5000));
    },
  },
  async mounted() {
    this.loading = true;
    try {
      await this.refreshAll();
    } finally {
      this.loading = false;
    }
    this.startPolling();
  },
  beforeUnmount() {
    this.intervals.forEach((id) => clearInterval(id));
  },
  template: `
    <div class="page-shell">
      <header class="topbar">
        <div>
          <div class="eyebrow">HmRAGCLI</div>
          <h1>Operations Dashboard</h1>
        </div>
        <div class="topbar-actions">
          <a class="btn btn-secondary" href="/ui/query/index.html">查询页面</a>
          <button class="btn btn-secondary" @click="openSourceModal">新增数据源</button>
          <button class="btn btn-secondary" :disabled="loadingHealth" @click="loadSystemHealth">
            {{ loadingHealth ? '刷新中...' : '刷新系统状态' }}
          </button>
        </div>
      </header>

      <nav class="tabs">
        <button class="tab" :class="{ active: activeTab === 'overview' }" @click="activeTab = 'overview'">总览</button>
        <button class="tab" :class="{ active: activeTab === 'files' }" @click="activeTab = 'files'; filesPage = 1; loadFiles()">文件</button>
        <button class="tab" :class="{ active: activeTab === 'jobs' }" @click="activeTab = 'jobs'; jobsPage = 1; loadJobs()">任务</button>
        <button class="tab" :class="{ active: activeTab === 'knowledge' }" @click="activeTab = 'knowledge'; loadKnowledge()">知识编译</button>
        <button class="tab" :class="{ active: activeTab === 'failures' }" @click="activeTab = 'failures'; failuresPage = 1; loadFailures()">失败</button>
      </nav>

      <section v-if="activeTab === 'overview'" class="stack">
        <section class="panel">
          <div class="panel-header"><h2>系统状态</h2></div>
          <div v-if="!systemHealth" class="muted">点击“刷新系统状态”获取运行信息</div>
          <div v-else class="metrics-grid">
            <div v-for="item in systemHealth.checks || []" :key="item.name" class="metric-card">
              <div class="status-row">
                <strong>{{ item.name }}</strong>
                <span class="pill" :class="item.ok ? 'ok' : 'fail'">{{ item.ok ? 'OK' : 'FAIL' }}</span>
              </div>
              <div class="muted">{{ item.error || JSON.stringify(item.detail || {}) }}</div>
            </div>
          </div>
        </section>

        <section class="kpi-grid">
          <div class="kpi-card"><div class="muted">数据源</div><div class="kpi-value">{{ overview.totalDataSources || 0 }}</div></div>
          <div class="kpi-card"><div class="muted">文件总数</div><div class="kpi-value">{{ overview.totalFiles || 0 }}</div></div>
          <div class="kpi-card"><div class="muted">已接收</div><div class="kpi-value">{{ overview.acceptedFiles || 0 }}</div></div>
          <div class="kpi-card"><div class="muted">排队中</div><div class="kpi-value">{{ overview.queuedFiles || 0 }}</div></div>
          <div class="kpi-card"><div class="muted">运行中</div><div class="kpi-value">{{ overview.runningFiles || 0 }}</div></div>
          <div class="kpi-card"><div class="muted">已完成</div><div class="kpi-value">{{ overview.readyFiles || 0 }}</div></div>
          <div class="kpi-card"><div class="muted">失败</div><div class="kpi-value">{{ overview.failedFiles || 0 }}</div></div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <h2>数据源</h2>
            <div class="filters">
              <button class="btn btn-secondary btn-small" :class="{ 'is-active-page': sourceView === 'active' }" @click="switchSourceView('active')">进行中</button>
              <button class="btn btn-secondary btn-small" :class="{ 'is-active-page': sourceView === 'completed' }" @click="switchSourceView('completed')">已完成</button>
            </div>
          </div>
          <div class="data-source-grid">
            <div v-for="source in visibleDataSources" :key="source.id" class="data-source-card">
              <div class="status-row">
                <strong>{{ source.sourceName }}</strong>
                <span class="pill info">{{ source.sourceType }}</span>
              </div>
              <div class="muted">{{ source.rootPath }}</div>
              <div class="data-source-stats">
                <div><strong>{{ source.totalFiles }}</strong><div class="muted">总文件</div></div>
                <div><strong>{{ source.acceptedFiles }}</strong><div class="muted">已接收</div></div>
                <div><strong>{{ source.queuedFiles }}</strong><div class="muted">排队</div></div>
                <div><strong>{{ source.runningFiles }}</strong><div class="muted">运行</div></div>
                <div><strong>{{ source.readyFiles }}</strong><div class="muted">完成</div></div>
                <div><strong>{{ source.failedFiles }}</strong><div class="muted">失败</div></div>
              </div>
              <div class="pipeline-grid">
                <div v-for="stageName in ['probe', 'parse', 'extract', 'vector']" :key="stageName" class="pipeline-item">
                  <div class="pipeline-head">
                    <strong>{{ stageLabel(stageName) }}</strong>
                    <span class="muted">{{ stagePercent(sourceStage(source, stageName)) }}%</span>
                  </div>
                  <div class="progress-bar"><div class="progress-fill" :class="sourceStageProgressClass(source, stageName)" :style="{ width: stagePercent(sourceStage(source, stageName)) + '%' }"></div></div>
                  <div class="muted">{{ sourceStageSummary(sourceStage(source, stageName)) }}</div>
                </div>
              </div>
              <div class="action-row">
                <button class="btn btn-secondary btn-small" @click="selectedSourceId = source.id; filesPage = 1; activeTab = 'files'; loadFiles()">查看文件</button>
                <button class="btn btn-secondary btn-small" :disabled="isSourceActionRunning(source.id, 'scan')" @click="triggerSourceAction(source.id, 'scan')">{{ isSourceActionRunning(source.id, 'scan') ? '扫描提交中...' : '扫描' }}</button>
                <button class="btn btn-secondary btn-small" :disabled="isSourceActionRunning(source.id, 'retryFailed')" @click="triggerSourceAction(source.id, 'retryFailed')">{{ isSourceActionRunning(source.id, 'retryFailed') ? '提交中...' : '重跑失败' }}</button>
                <button class="btn btn-primary btn-small" :disabled="isSourceActionRunning(source.id, 'ingest')" @click="triggerSourceAction(source.id, 'ingest')">{{ isSourceActionRunning(source.id, 'ingest') ? '入库提交中...' : '入库' }}</button>
                <button class="btn btn-secondary btn-small" :disabled="isSourceActionRunning(source.id, 'cancel')" @click="cancelSourceJobs(source)">{{ isSourceActionRunning(source.id, 'cancel') ? '停止提交中...' : '停止任务' }}</button>
                <button class="btn btn-secondary btn-small" :disabled="isSourceActionRunning(source.id, 'approve')" @click="approveDegradedProcessing(source)">{{ isSourceActionRunning(source.id, 'approve') ? '批准提交中...' : '批准降级' }}</button>
                <button class="btn btn-danger btn-small" :disabled="isSourceActionRunning(source.id, 'reset')" @click="resetSourceIndex(source)">{{ isSourceActionRunning(source.id, 'reset') ? '清理中...' : '清理索引' }}</button>
                <button class="btn btn-danger btn-small" :disabled="isSourceActionRunning(source.id, 'delete')" @click="removeDataSource(source)">{{ isSourceActionRunning(source.id, 'delete') ? '删除中...' : '删除数据源' }}</button>
              </div>
            </div>
          </div>
          <div class="pagination">
            <div class="muted">第 {{ sourcePage }} / {{ sourceTotalPages }} 页，共 {{ filteredDataSources.length }} 个数据源</div>
            <button class="btn btn-secondary btn-small" :disabled="sourcePage <= 1" @click="changeSourcePage(sourcePage - 1)">上一页</button>
            <button v-for="page in pageWindow(sourcePage, sourceTotalPages)" :key="'sources-' + page" class="btn btn-secondary btn-small" :class="{ 'is-active-page': page === sourcePage }" @click="changeSourcePage(page)">{{ page }}</button>
            <button class="btn btn-secondary btn-small" :disabled="sourcePage >= sourceTotalPages" @click="changeSourcePage(sourcePage + 1)">下一页</button>
          </div>
        </section>

        <section class="panel">
          <div class="panel-header"><h2>活跃文件</h2></div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>文件</th>
                  <th>状态</th>
                  <th>当前阶段</th>
                  <th>进度</th>
                  <th>错误</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!activeFiles.length"><td colspan="5" class="empty">暂无活跃文件</td></tr>
                <tr v-for="file in activeFiles" :key="file.id">
                  <td><strong>{{ file.fileName }}</strong><div class="muted">{{ file.dataSourceName || '-' }}</div></td>
                  <td><span class="pill" :class="statusClass(file.lifecycleStatus)">{{ file.lifecycleStatus }}</span></td>
                  <td>{{ stageLabel(file.currentStage) }}</td>
                  <td>
                    <div class="progress-row">
                      <div>{{ file.progressPercent }}%</div>
                      <div class="progress-bar"><div class="progress-fill" :class="fileTotalProgressClass(file)" :style="{ width: file.progressPercent + '%' }"></div></div>
                    </div>
                  </td>
                  <td><span class="muted">{{ file.errorSummary || '-' }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </section>

      <section v-if="activeTab === 'files'" class="panel">
        <div class="panel-header">
          <h2>文件列表</h2>
          <div class="filters">
            <select v-model="selectedSourceId" @change="filesPage = 1; loadFiles()">
              <option value="">选择数据源</option>
              <option v-for="source in sourceOptions" :key="source.id" :value="source.id">{{ source.name }}</option>
            </select>
          </div>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>文件</th>
                <th>生命周期</th>
                <th>当前阶段</th>
                <th>总进度</th>
                <th>四段进度</th>
                <th>错误</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!files.length"><td colspan="6" class="empty">当前没有文件</td></tr>
              <tr v-for="file in files" :key="file.id">
                <td><strong>{{ file.fileName }}</strong><div class="muted">{{ file.relativePath || file.filePath }}</div></td>
                <td><span class="pill" :class="statusClass(file.lifecycleStatus)">{{ file.lifecycleLabel }}</span></td>
                <td>
                  <div>{{ stageLabel(file.currentStage) }}</div>
                  <div class="muted">{{ fileCurrentStageDetail(file) }}</div>
                </td>
                <td>
                  <div class="progress-row">
                    <div>{{ file.progressPercent }}%</div>
                    <div class="progress-bar"><div class="progress-fill" :class="fileTotalProgressClass(file)" :style="{ width: file.progressPercent + '%' }"></div></div>
                  </div>
                </td>
                <td>
                  <div class="stage-stack">
                    <div v-for="stageName in ['probe', 'parse', 'extract', 'vector']" :key="stageName" class="stage-chip" :class="statusClass(fileStage(file, stageName)?.status)">
                      <span>{{ stageLabel(stageName) }}</span>
                      <strong>{{ stageSummary(fileStage(file, stageName)) }}</strong>
                    </div>
                    <div v-for="stageName in ['probe', 'parse', 'extract', 'vector']" :key="'detail-' + stageName" class="stage-detail" :class="{ 'is-stale': isStaleStage(fileStage(file, stageName)) }">
                      {{ stageLabel(stageName) }}: {{ stageStatusText(fileStage(file, stageName)) }}
                    </div>
                  </div>
                </td>
                <td>
                  <div>{{ file.errorSummary || '-' }}</div>
                  <div class="muted">{{ file.errorDetail || '-' }}</div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <div class="muted">第 {{ filesPage }} / {{ filesTotalPages }} 页，共 {{ filesTotal }} 条</div>
          <button class="btn btn-secondary btn-small" :disabled="filesPage <= 1" @click="changeFilesPage(filesPage - 1)">上一页</button>
          <button v-for="page in pageWindow(filesPage, filesTotalPages)" :key="'files-' + page" class="btn btn-secondary btn-small" :class="{ 'is-active-page': page === filesPage }" @click="changeFilesPage(page)">{{ page }}</button>
          <button class="btn btn-secondary btn-small" :disabled="filesPage >= filesTotalPages" @click="changeFilesPage(filesPage + 1)">下一页</button>
        </div>
      </section>

      <section v-if="activeTab === 'jobs'" class="panel">
        <div class="panel-header"><h2>任务队列</h2></div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>类型</th>
                <th>数据源</th>
                <th>状态</th>
                <th>文件进度</th>
                <th>阶段分布</th>
                <th>开始时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!jobs.length"><td colspan="6" class="empty">暂无任务</td></tr>
              <tr v-for="job in jobs" :key="job.id">
                <td><span class="pill info">{{ job.jobKind }}</span></td>
                <td>{{ job.dataSourceName || '-' }}</td>
                <td><span class="pill" :class="statusClass(job.status)">{{ job.status }}</span></td>
                <td>
                  <div class="progress-row">
                    <div>{{ job.completedFiles }}/{{ job.totalFiles }} ({{ job.progressPercent }}%)</div>
                    <div class="progress-bar"><div class="progress-fill" :class="jobProgressClass(job)" :style="{ width: job.progressPercent + '%' }"></div></div>
                    <div class="muted">{{ job.currentStageSummary }}</div>
                  </div>
                </td>
                <td>
                  <div class="stage-stack">
                    <div v-for="stage in job.stages || []" :key="stage.stage" class="stage-chip info">
                      <span>{{ stageLabel(stage.stage) }}</span>
                      <strong>{{ stage.completedUnits }}/{{ stage.totalUnits }}</strong>
                    </div>
                  </div>
                </td>
                <td>{{ formatDate(job.startedAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <div class="muted">第 {{ jobsPage }} / {{ jobsTotalPages }} 页，共 {{ jobsTotal }} 条</div>
          <button class="btn btn-secondary btn-small" :disabled="jobsPage <= 1" @click="changeJobsPage(jobsPage - 1)">上一页</button>
          <button v-for="page in pageWindow(jobsPage, jobsTotalPages)" :key="'jobs-' + page" class="btn btn-secondary btn-small" :class="{ 'is-active-page': page === jobsPage }" @click="changeJobsPage(page)">{{ page }}</button>
          <button class="btn btn-secondary btn-small" :disabled="jobsPage >= jobsTotalPages" @click="changeJobsPage(jobsPage + 1)">下一页</button>
        </div>
      </section>

      <section v-if="activeTab === 'knowledge'" class="stack">
        <section class="panel">
          <div class="panel-header">
            <h2>领域知识编译</h2>
            <div class="filters">
              <select v-model="selectedDomainKnowledgeId" @change="changeKnowledgeDomain">
                <option value="">全部领域</option>
                <option v-for="domain in knowledgeDomains" :key="domain.id" :value="domain.id">{{ domain.name }}</option>
              </select>
              <button class="btn btn-secondary btn-small" @click="openKnowledgeDomainModal(selectedKnowledgeDomain)">编辑当前领域</button>
              <button class="btn btn-danger btn-small" :disabled="!selectedKnowledgeDomain" @click="deleteKnowledgeDomain(selectedKnowledgeDomain)">删除当前领域</button>
              <button class="btn btn-secondary btn-small" @click="openKnowledgeDomainModal()">新增领域</button>
              <button class="btn btn-secondary btn-small" :disabled="!selectedDomainKnowledgeId" @click="openKnowledgeTopicModal()">新增专题</button>
              <button class="btn btn-secondary btn-small" :disabled="loadingKnowledge" @click="loadKnowledge()">
                {{ loadingKnowledge ? '刷新中...' : '刷新知识状态' }}
              </button>
            </div>
          </div>
          <div v-if="selectedKnowledgeDomain" class="muted">
            当前领域：{{ selectedKnowledgeDomain.name }} · 状态 {{ selectedKnowledgeDomain.status }} · 自动维护 {{ selectedKnowledgeDomain.autoRefreshEnabled ? '开启' : '关闭' }}
          </div>
          <div v-else class="muted">当前未选择领域，显示全部精炼任务和知识包。</div>
          <div class="action-row" style="margin-top: 14px;">
            <input
              v-model="knowledgeRefineForm.modelProfile"
              placeholder="可选：modelProfile"
              style="min-width: 220px;"
            />
            <select v-model="knowledgeResultFilter" @change="changeKnowledgeResultFilter">
              <option value="all">全部结果</option>
              <option value="user">仅人工</option>
              <option value="auto">仅自动</option>
            </select>
            <button
              class="btn btn-primary btn-small"
              :disabled="!selectedDomainKnowledgeId || knowledgeActionState"
              @click="startKnowledgeRefine"
            >
              {{ knowledgeActionState ? '提交中...' : '立即精炼当前领域' }}
            </button>
          </div>
          <div class="stage-stack" style="margin-top: 14px;">
            <div class="stage-detail">自动任务 {{ knowledgeAutoStats.jobs }} 个，自动知识包 {{ knowledgeAutoStats.packs }} 个</div>
            <div class="stage-detail">最近自动任务 {{ formatDate(knowledgeAutoStats.latestJobAt) }}</div>
            <div class="stage-detail">最近自动结果 {{ formatDate(knowledgeAutoStats.latestPackAt) }}</div>
          </div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <h2>自动汇聚异常</h2>
            <div class="muted">仅显示自动任务中的 paused / failed</div>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>任务</th>
                  <th>状态</th>
                  <th>原因</th>
                  <th>草稿摘要</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!autoProblemJobs.length"><td colspan="5" class="empty">当前没有自动汇聚异常</td></tr>
                <tr v-for="job in autoProblemJobs" :key="'auto-problem-' + job.id">
                  <td>
                    <span class="pill ok">自动</span>
                    <span class="pill info">{{ job.jobType }}</span>
                    <div class="muted">{{ job.id }}</div>
                  </td>
                  <td>
                    <span class="pill" :class="statusClass(job.status)">{{ job.status }}</span>
                    <div class="muted">{{ formatDate(job.updatedAt) }}</div>
                  </td>
                  <td>
                    <div>{{ job.errorMessage || '-' }}</div>
                    <pre v-if="job.outputSummary && job.outputSummary.pauseMetadata" class="json-preview">{{ formatJsonBrief(job.outputSummary.pauseMetadata) }}</pre>
                  </td>
                  <td>{{ shorten(job.outputSummary?.draftSummary || '-') }}</td>
                  <td>
                    <button v-if="job.status === 'paused'" class="btn btn-secondary btn-small" @click="resumeRefineJob(job)">继续任务</button>
                    <span v-else class="muted">需排查后重建</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <h2>自动汇聚趋势</h2>
            <div class="muted">最近 12 次自动任务的产出和状态</div>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>时间</th>
                  <th>任务</th>
                  <th>状态</th>
                  <th>知识包</th>
                  <th>证据数</th>
                  <th>LLM精炼包</th>
                  <th>原因/备注</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!autoRunTrend.length"><td colspan="7" class="empty">暂无自动汇聚历史</td></tr>
                <tr v-for="item in autoRunTrend" :key="'auto-trend-' + item.id">
                  <td>
                    <div>{{ formatDate(item.updatedAt || item.createdAt) }}</div>
                    <div class="muted">{{ item.finishedAt ? ('完成 ' + formatDate(item.finishedAt)) : '-' }}</div>
                  </td>
                  <td>
                    <span class="pill ok">自动</span>
                    <span class="pill info">{{ item.jobType }}</span>
                    <div class="muted">{{ item.modelProfile || '-' }}</div>
                  </td>
                  <td><span class="pill" :class="statusClass(item.status)">{{ item.status }}</span></td>
                  <td>{{ item.packCount }}</td>
                  <td>{{ item.evidenceCount }}</td>
                  <td>{{ item.refinedPackCount }}</td>
                  <td>{{ shorten(item.pausedReason || '-', 120) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <h2>自动结果对比</h2>
            <div class="muted">同一领域最近两次自动知识包的差异</div>
          </div>
          <div v-if="!autoPackComparison" class="empty">自动知识包不足两次，暂时无法对比。</div>
          <div v-else class="two-col">
            <div class="panel">
              <div class="panel-header">
                <h2>版本信息</h2>
                <div class="muted">{{ autoPackComparison.latest.title }}</div>
              </div>
              <div class="stage-stack">
                <div class="stage-detail">最新：{{ formatDate(autoPackComparison.latest.updatedAt || autoPackComparison.latest.createdAt) }}</div>
                <div class="stage-detail">上一版：{{ formatDate(autoPackComparison.previous.updatedAt || autoPackComparison.previous.createdAt) }}</div>
                <div class="stage-detail">最新证据数：{{ (autoPackComparison.latest.evidenceRefs || []).length }}</div>
                <div class="stage-detail">上一版证据数：{{ (autoPackComparison.previous.evidenceRefs || []).length }}</div>
                <div class="stage-detail">摘要差异：</div>
                <pre class="json-preview">{{ summaryDiff(autoPackComparison.latest.summary, autoPackComparison.previous.summary) }}</pre>
              </div>
            </div>
            <div class="panel">
              <div class="panel-header">
                <h2>关键要点变化</h2>
                <div class="muted">新增 {{ autoPackComparison.addedPoints.length }} / 移除 {{ autoPackComparison.removedPoints.length }}</div>
              </div>
              <div class="stage-stack">
                <div class="stage-detail"><strong>新增要点</strong></div>
                <div v-if="!autoPackComparison.addedPoints.length" class="muted">无新增</div>
                <div v-for="point in autoPackComparison.addedPoints" :key="'added-' + point" class="stage-detail">{{ point }}</div>
                <div class="stage-detail" style="margin-top: 8px;"><strong>移除要点</strong></div>
                <div v-if="!autoPackComparison.removedPoints.length" class="muted">无移除</div>
                <div v-for="point in autoPackComparison.removedPoints" :key="'removed-' + point" class="stage-detail">{{ point }}</div>
              </div>
            </div>
          </div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <h2>智能体优先上下文</h2>
            <div class="muted">当前领域默认消费顺序：accepted -> ready -> reference</div>
          </div>
          <div class="stage-stack" style="margin-bottom: 14px;">
            <div class="stage-detail">当前命中层级：{{ knowledgeAgentContextMode === 'accepted' ? '确认采用' : knowledgeAgentContextMode === 'ready' ? '待确认草稿' : knowledgeAgentContextMode === 'reference' ? '仅供参考回退' : '暂无可用知识包' }}</div>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>标题</th>
                  <th>人工结论</th>
                  <th>来源</th>
                  <th>精炼状态</th>
                  <th>证据数</th>
                  <th>更新时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!knowledgeAgentContextPacks.length"><td colspan="7" class="empty">当前领域暂无可供智能体消费的知识包</td></tr>
                <tr v-for="pack in knowledgeAgentContextPacks" :key="'agent-context-' + pack.id">
                  <td>
                    <strong>{{ pack.title }}</strong>
                    <div class="muted">{{ pack.id }}</div>
                  </td>
                  <td><span class="pill" :class="packReviewClass(pack)">{{ packReviewLabel(pack) }}</span></td>
                  <td><span class="pill" :class="triggerSourceClass(pack.triggerSource)">{{ triggerSourceLabel(pack.triggerSource) }}</span></td>
                  <td>
                    <span class="pill" :class="packRefinementClass(pack)">{{ packRefinementState(pack) }}</span>
                    <div class="muted">{{ packRefinementModel(pack) }}</div>
                  </td>
                  <td>{{ (pack.evidenceRefs || []).length }}</td>
                  <td>{{ formatDate(pack.updatedAt) }}</td>
                  <td>
                    <div class="table-actions">
                      <button class="btn btn-secondary btn-small" @click="openKnowledgePack(pack)">查看详情</button>
                      <button class="btn btn-secondary btn-small" :disabled="knowledgeActionState" @click="reviewKnowledgePack(pack, 'accepted')">确认采用</button>
                      <button class="btn btn-secondary btn-small" :disabled="knowledgeActionState" @click="reviewKnowledgePack(pack, 'reference')">仅供参考</button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-header"><h2>专题列表</h2></div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>专题</th>
                  <th>状态</th>
                  <th>优先级</th>
                  <th>种子问题</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!knowledgeTopics.length"><td colspan="4" class="empty">当前领域暂无专题</td></tr>
                <tr v-for="topic in knowledgeTopics" :key="topic.id">
                  <td>
                    <strong>{{ topic.name }}</strong>
                    <div class="muted">{{ topic.description || '-' }}</div>
                      <div class="table-actions" style="margin-top: 8px;">
                        <button class="btn btn-secondary btn-small" @click="openKnowledgeTopicModal(topic)">编辑专题</button>
                        <button class="btn btn-danger btn-small" @click="deleteKnowledgeTopic(topic)">删除专题</button>
                        <button class="btn btn-secondary btn-small" :disabled="knowledgeActionState" @click="startTopicRefine(topic)">
                          {{ knowledgeActionState ? '提交中...' : '精炼专题' }}
                        </button>
                    </div>
                  </td>
                  <td><span class="pill" :class="statusClass(topic.status)">{{ topic.status }}</span></td>
                  <td>{{ topic.priority }}</td>
                  <td>{{ (topic.seedQueries || []).join(' / ') || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-header"><h2>精炼任务</h2></div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>类型</th>
                  <th>状态</th>
                  <th>来源</th>
                  <th>模型</th>
                  <th>暂停/错误</th>
                  <th>草稿摘要</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!filteredKnowledgeJobs.length"><td colspan="7" class="empty">当前筛选条件下暂无领域知识精炼任务</td></tr>
                <tr v-for="job in filteredKnowledgeJobs" :key="job.id">
                  <td>
                    <span class="pill info">{{ job.jobType }}</span>
                    <div class="muted">{{ job.id }}</div>
                  </td>
                  <td>
                    <span class="pill" :class="statusClass(job.status)">{{ job.status }}</span>
                    <div class="muted">{{ formatDate(job.updatedAt) }}</div>
                  </td>
                  <td><span class="pill" :class="triggerSourceClass(job.triggerSource)">{{ triggerSourceLabel(job.triggerSource) }}</span></td>
                  <td><span class="muted">{{ job.modelProfile || '-' }}</span></td>
                  <td>
                    <div>{{ job.errorMessage || '-' }}</div>
                    <pre v-if="job.outputSummary && job.outputSummary.pauseMetadata" class="json-preview">{{ formatJsonBrief(job.outputSummary.pauseMetadata) }}</pre>
                  </td>
                  <td>{{ shorten(job.outputSummary?.draftSummary || '-') }}</td>
                  <td>
                    <button v-if="job.status === 'paused'" class="btn btn-secondary btn-small" @click="resumeRefineJob(job)">继续任务</button>
                    <span v-else class="muted">-</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-header"><h2>知识包</h2></div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>标题</th>
                  <th>类型</th>
                  <th>来源</th>
                  <th>人工结论</th>
                  <th>精炼状态</th>
                  <th>摘要</th>
                  <th>关键要点</th>
                  <th>证据数</th>
                  <th>更新时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!filteredKnowledgePacks.length"><td colspan="9" class="empty">当前筛选条件下暂无知识包</td></tr>
                <tr v-for="pack in filteredKnowledgePacks" :key="pack.id">
                  <td>
                    <strong>{{ pack.title }}</strong>
                    <div class="muted">{{ pack.id }}</div>
                    <div class="table-actions" style="margin-top: 8px;">
                      <button class="btn btn-secondary btn-small" @click="openKnowledgePack(pack)">查看证据</button>
                      <button class="btn btn-secondary btn-small" :disabled="knowledgeActionState" @click="reviewKnowledgePack(pack, 'accepted')">确认采用</button>
                      <button class="btn btn-secondary btn-small" :disabled="knowledgeActionState" @click="reviewKnowledgePack(pack, 'reference')">仅供参考</button>
                      <button class="btn btn-secondary btn-small" :disabled="knowledgeActionState" @click="rerunPackRefine(pack)">
                        {{ knowledgeActionState ? '提交中...' : '重新精炼' }}
                      </button>
                    </div>
                  </td>
                  <td>
                    <span class="pill" :class="statusClass(pack.status)">{{ pack.artifactType }}</span>
                    <div class="muted">状态 {{ pack.status }}</div>
                  </td>
                  <td><span class="pill" :class="triggerSourceClass(pack.triggerSource)">{{ triggerSourceLabel(pack.triggerSource) }}</span></td>
                  <td>
                    <span class="pill" :class="packReviewClass(pack)">{{ packReviewLabel(pack) }}</span>
                    <div class="muted">{{ packReview(pack)?.reviewedBy || '-' }}</div>
                  </td>
                  <td>
                    <span class="pill" :class="packRefinementClass(pack)">{{ packRefinementState(pack) }}</span>
                    <div class="muted">{{ packRefinementModel(pack) }}</div>
                  </td>
                  <td>{{ shorten(pack.summary || '-') }}</td>
                  <td>
                    <div class="stage-stack">
                      <div v-for="point in (pack.keyPoints || []).slice(0, 4)" :key="point" class="stage-detail">{{ point }}</div>
                    </div>
                  </td>
                  <td>{{ (pack.evidenceRefs || []).length }}</td>
                  <td>{{ formatDate(pack.updatedAt) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section v-if="selectedKnowledgePack" class="panel">
          <div class="panel-header">
            <h2>知识包详情</h2>
            <div class="muted">{{ selectedKnowledgePackTitle }}</div>
          </div>
          <div class="stage-stack" style="margin-bottom: 14px;">
            <div class="stage-detail">来源：{{ triggerSourceLabel(selectedKnowledgePack.triggerSource) }} · 证据数 {{ selectedKnowledgePackEvidenceCount }}</div>
            <div class="stage-detail">人工结论：{{ packReviewLabel(selectedKnowledgePack) }}</div>
            <div class="stage-detail">精炼状态：{{ packRefinementState(selectedKnowledgePack) }}</div>
            <div class="stage-detail">模型：{{ packRefinementModel(selectedKnowledgePack) }}</div>
            <pre v-if="selectedKnowledgePackReview" class="json-preview">{{ formatJsonBrief(selectedKnowledgePackReview) }}</pre>
            <pre v-if="selectedKnowledgePackRefinement" class="json-preview">{{ formatJsonBrief(selectedKnowledgePackRefinement) }}</pre>
            <div class="table-actions">
              <button class="btn btn-secondary btn-small" :disabled="knowledgeActionState" @click="reviewKnowledgePack(selectedKnowledgePack, 'accepted')">确认采用</button>
              <button class="btn btn-secondary btn-small" :disabled="knowledgeActionState" @click="reviewKnowledgePack(selectedKnowledgePack, 'reference')">仅供参考</button>
            </div>
          </div>
          <div class="two-col">
            <div class="panel">
              <div class="panel-header">
                <h2>证据列表</h2>
                <div class="muted">{{ loadingKnowledgeDetail ? '加载中...' : ('共 ' + selectedKnowledgeEvidence.length + ' 条') }}</div>
              </div>
              <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>类型</th>
                      <th>标题/片段</th>
                      <th>来源</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-if="!selectedKnowledgeEvidence.length"><td colspan="4" class="empty">当前知识包暂无证据</td></tr>
                    <tr v-for="item in selectedKnowledgeEvidence" :key="item.evidenceRef">
                      <td><span class="pill info">{{ item.evidenceType }}</span></td>
                      <td>
                        <strong>{{ item.title || '-' }}</strong>
                        <div class="muted">{{ shorten(item.snippet || '-', 160) }}</div>
                      </td>
                      <td>
                        <div>{{ item.sourceFile || '-' }}</div>
                        <div class="muted">页码 {{ item.pageNo || '-' }}</div>
                      </td>
                      <td>
                        <button class="btn btn-secondary btn-small" @click="loadEvidenceContext(item)">查看上下文</button>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            <div class="panel">
              <div class="panel-header">
                <h2>正文回溯</h2>
                <div class="muted">{{ selectedEvidenceContext ? selectedEvidenceContext.evidenceRef : '未选择证据' }}</div>
              </div>
              <div v-if="!selectedEvidenceContext" class="empty">点击左侧“查看上下文”加载正文片段。</div>
              <div v-else class="stage-stack">
                <div class="stage-detail">
                  <strong>标题：</strong>{{ selectedEvidenceContext.title || '-' }}
                </div>
                <div class="stage-detail">
                  <strong>来源：</strong>{{ selectedEvidenceContext.sourceFile || '-' }}
                </div>
                <div class="stage-detail">
                  <strong>正文：</strong>{{ selectedEvidenceContext.content || '-' }}
                </div>
                <pre class="json-preview">{{ selectedEvidenceContext.context || '-' }}</pre>
              </div>
            </div>
          </div>
        </section>
      </section>

      <section v-if="activeTab === 'failures'" class="panel">
        <div class="panel-header">
          <h2>失败记录</h2>
          <div class="filters">
            <button class="btn btn-secondary btn-small" @click="cleanupTempFailures">清理临时文件错误</button>
          </div>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>文件</th>
                <th>失败阶段</th>
                <th>原因</th>
                <th>详情</th>
                <th>时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!failures.length"><td colspan="5" class="empty">暂无失败文件</td></tr>
              <tr v-for="file in failures" :key="file.id">
                <td><strong>{{ file.fileName }}</strong><div class="muted">{{ file.dataSourceName || '-' }}</div></td>
                <td><span class="pill fail">{{ file.failedStage || '-' }}</span></td>
                <td>{{ file.errorSummary || '-' }}</td>
                <td><span class="muted">{{ file.errorDetail || '-' }}</span></td>
                <td>{{ formatDate(file.updatedAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <div class="muted">第 {{ failuresPage }} / {{ failuresTotalPages }} 页，共 {{ failuresTotal }} 条</div>
          <button class="btn btn-secondary btn-small" :disabled="failuresPage <= 1" @click="changeFailuresPage(failuresPage - 1)">上一页</button>
          <button v-for="page in pageWindow(failuresPage, failuresTotalPages)" :key="'failures-' + page" class="btn btn-secondary btn-small" :class="{ 'is-active-page': page === failuresPage }" @click="changeFailuresPage(page)">{{ page }}</button>
          <button class="btn btn-secondary btn-small" :disabled="failuresPage >= failuresTotalPages" @click="changeFailuresPage(failuresPage + 1)">下一页</button>
        </div>
      </section>

      <div class="modal" :class="{ 'is-open': sourceModalOpen }">
        <div class="modal-mask" @click="closeSourceModal"></div>
        <div class="modal-panel">
          <div class="drawer-header">
            <div>
              <div class="eyebrow">Create Data Source</div>
              <h3>新增数据源</h3>
            </div>
            <button class="btn btn-secondary btn-small" @click="closeSourceModal">关闭</button>
          </div>
          <form class="modal-form" @submit.prevent="createSource">
            <label><span>数据源名称</span><input v-model="sourceForm.sourceName" required /></label>
            <label><span>根目录路径</span><input v-model="sourceForm.rootPath" required /></label>
            <label><span>包含规则</span><input v-model="sourceForm.includePatterns" /></label>
            <label><span>排除规则</span><input v-model="sourceForm.excludePatterns" /></label>
            <label class="checkbox-row"><input v-model="sourceForm.recursive" type="checkbox" /><span>递归扫描子文件夹</span></label>
            <div class="modal-actions">
              <button type="button" class="btn btn-secondary" @click="closeSourceModal">取消</button>
              <button type="submit" class="btn btn-primary">创建</button>
            </div>
          </form>
        </div>
      </div>

      <div class="modal" :class="{ 'is-open': knowledgeDomainModalOpen }">
        <div class="modal-mask" @click="closeKnowledgeDomainModal"></div>
        <div class="modal-panel">
          <div class="drawer-header">
            <div>
              <div class="eyebrow">Knowledge Domain</div>
              <h3>{{ editingKnowledgeDomainId ? '编辑领域' : '新增领域' }}</h3>
            </div>
            <button class="btn btn-secondary btn-small" @click="closeKnowledgeDomainModal">关闭</button>
          </div>
          <form class="modal-form" @submit.prevent="saveKnowledgeDomain">
            <label><span>领域名称</span><input v-model="knowledgeDomainForm.name" required /></label>
            <label class="checkbox-row"><input v-model="knowledgeDomainForm.autoRefreshEnabled" type="checkbox" /><span>启用自动维护</span></label>
            <div v-if="knowledgeDomainForm.autoRefreshEnabled" class="two-col">
              <label>
                <span>执行频率</span>
                <select v-model="knowledgeDomainForm.autoRefreshMode">
                  <option value="daily">每天</option>
                  <option value="weekly">每周</option>
                </select>
              </label>
              <label>
                <span>执行时间</span>
                <input v-model="knowledgeDomainForm.autoRefreshTime" type="time" />
              </label>
            </div>
            <label v-if="knowledgeDomainForm.autoRefreshEnabled && knowledgeDomainForm.autoRefreshMode === 'weekly'">
              <span>每周哪天</span>
              <select v-model="knowledgeDomainForm.autoRefreshWeekday">
                <option value="MON">周一</option>
                <option value="TUE">周二</option>
                <option value="WED">周三</option>
                <option value="THU">周四</option>
                <option value="FRI">周五</option>
                <option value="SAT">周六</option>
                <option value="SUN">周日</option>
              </select>
            </label>
            <div class="panel knowledge-assistant-panel" style="margin: 0;">
              <div class="panel-header">
                <h2>AI 引导设置</h2>
                <button type="button" class="btn btn-secondary btn-small" :disabled="knowledgeActionState" @click="runDomainSetupAssistant">
                  {{ knowledgeActionState ? '处理中...' : '开始AI引导' }}
                </button>
              </div>
              <div class="muted">系统会围绕“如何为该领域构建知识库”逐步提问，帮助形成知识精炼范围、检索入口和证据组织方式。</div>
              <div class="assistant-layout">
                <div class="assistant-column">
                  <div class="assistant-section-title">对话</div>
                  <div class="assistant-chatlog">
                    <div v-for="(message, index) in knowledgeDomainForm.assistantHistory" :key="'assistant-history-' + index" class="stage-detail" :class="{ 'assistant-user': message.role !== 'assistant', 'assistant-system': message.role === 'assistant' }">
                      <strong>{{ message.role === 'assistant' ? '系统' : '用户' }}：</strong>{{ message.content }}
                    </div>
                    <div v-if="knowledgeDomainForm.assistantQuestion" class="stage-detail assistant-system current-question">
                      <strong>当前问题：</strong>{{ knowledgeDomainForm.assistantQuestion }}
                    </div>
                    <div v-if="knowledgeDomainForm.assistantReason" class="stage-detail" :class="{ 'is-error': /失败|错误|超时|未配置/.test(String(knowledgeDomainForm.assistantReason || '')) }">
                      <strong>模型状态：</strong>{{ knowledgeDomainForm.assistantReason }}
                    </div>
                    <div v-if="knowledgeDomainForm.assistantStreamingPreview" class="stage-detail assistant-preview">
                      <strong>流式预览：</strong>{{ knowledgeDomainForm.assistantStreamingPreview }}
                    </div>
                  </div>
                  <div class="assistant-input-block">
                    <label>
                      <span>你的回答</span>
                      <textarea
                        v-model="knowledgeDomainForm.assistantAnswer"
                        class="assistant-answer-box"
                        placeholder="直接说明你希望这个领域知识库重点覆盖什么、服务什么场景、哪些信息必须能回溯到正文。"
                      ></textarea>
                    </label>
                    <div class="table-actions">
                      <button type="button" class="btn btn-secondary" :disabled="knowledgeActionState || !knowledgeDomainForm.assistantQuestion" @click="sendDomainSetupAnswer">
                        {{ knowledgeActionState ? '提交中...' : '发送回答' }}
                      </button>
                    </div>
                  </div>
                </div>
                <div class="assistant-column">
                  <div class="assistant-section-title">当前知识库草稿</div>
                  <div class="stage-stack assistant-draft-stack">
                    <div class="stage-detail">
                      <strong>当前维度</strong>
                      <div>{{ knowledgeDomainForm.assistantCurrentDimension || '-' }}</div>
                    </div>
                    <div class="stage-detail">
                      <strong>已覆盖维度</strong>
                      <div>{{ (knowledgeDomainForm.assistantCoveredDimensions || []).join(' / ') || '-' }}</div>
                    </div>
                    <div class="stage-detail">
                      <strong>下一步维度</strong>
                      <div>{{ knowledgeDomainForm.assistantNextDimension || '-' }}</div>
                    </div>
                    <div class="stage-detail"><strong>目标</strong><div>{{ knowledgeDomainForm.assistantDraft.goal || '-' }}</div></div>
                    <div class="stage-detail"><strong>范围说明</strong><div>{{ knowledgeDomainForm.assistantDraft.description || '-' }}</div></div>
                    <div class="stage-detail"><strong>种子问题</strong><div>{{ (knowledgeDomainForm.assistantDraft.seedQueries || []).join(' / ') || '-' }}</div></div>
                    <div class="stage-detail"><strong>当前状态</strong><div>{{ knowledgeDomainForm.assistantReady ? '已可保存' : '仍需继续回答' }}</div></div>
                  </div>
                </div>
              </div>
            </div>
            <div class="modal-actions">
              <button type="button" class="btn btn-secondary" @click="closeKnowledgeDomainModal">取消</button>
              <button type="submit" class="btn btn-primary">保存</button>
            </div>
          </form>
        </div>
      </div>

      <div class="modal" :class="{ 'is-open': knowledgeTopicModalOpen }">
        <div class="modal-mask" @click="closeKnowledgeTopicModal"></div>
        <div class="modal-panel">
          <div class="drawer-header">
            <div>
              <div class="eyebrow">Knowledge Topic</div>
              <h3>{{ editingKnowledgeTopicId ? '编辑专题' : '新增专题' }}</h3>
            </div>
            <button class="btn btn-secondary btn-small" @click="closeKnowledgeTopicModal">关闭</button>
          </div>
          <form class="modal-form" @submit.prevent="saveKnowledgeTopic">
            <label><span>专题名称</span><input v-model="knowledgeTopicForm.name" required /></label>
            <label><span>专题说明</span><input v-model="knowledgeTopicForm.description" /></label>
            <label><span>种子问题（每行一条）</span><textarea v-model="knowledgeTopicForm.seedQueries"></textarea></label>
            <label><span>优先级</span><input v-model="knowledgeTopicForm.priority" type="number" /></label>
            <label><span>状态</span><input v-model="knowledgeTopicForm.status" /></label>
            <div class="modal-actions">
              <button type="button" class="btn btn-secondary" @click="closeKnowledgeTopicModal">取消</button>
              <button type="submit" class="btn btn-primary">{{ editingKnowledgeTopicId ? '保存' : '创建' }}</button>
            </div>
          </form>
        </div>
      </div>

      <div class="toast" :class="{ 'is-visible': !!toast }">{{ toast }}</div>
    </div>
  `,
}).mount("#app");
