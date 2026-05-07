const { createApp } = Vue;

const api = {
  search: "/api/v1/search",
  qa: "/api/v1/qa/query",
  overview: "/api/v1/documents",
};

createApp({
  data() {
    return {
      mode: "qa",
      queryText: "",
      topK: 5,
      excludeDevDocs: true,
      matchTypeFilter: "all",
      loading: false,
      answer: null,
      searchResult: null,
      error: "",
      toast: "",
      overviewModal: null,
      loadingOverviewId: "",
    };
  },
  computed: {
    canSubmit() {
      return !!this.queryText.trim() && !this.loading;
    },
    normalizedStructuredAnswer() {
      return this.answer?.structuredAnswer || this.answer?.structured_answer || null;
    },
    structuredEntries() {
      const value = this.normalizedStructuredAnswer;
      if (!value) return [];
      const labels = {
        subject: "主题",
        action: "动作",
        constraint: "约束",
        exception: "例外",
        indicator: "指标",
        value: "数值",
        unitName: "单位",
        unit_name: "单位",
        time: "时间",
        region: "地区",
      };
      return Object.entries(labels)
        .map(([key, label]) => ({ key, label, value: value[key] }))
        .filter((item) => item.value !== null && item.value !== undefined && item.value !== "");
    },
    normalizedDocHits() {
      return this.searchResult?.docHits || this.searchResult?.doc_hits || [];
    },
    normalizedEvidenceHits() {
      return this.searchResult?.evidenceHits || this.searchResult?.evidence_hits || this.searchResult?.items || [];
    },
    filteredSearchItems() {
      const items = this.normalizedEvidenceHits;
      if (this.matchTypeFilter === "all") return items;
      return items.filter((item) => {
        const mt = item.matchType || item.match_type;
        return mt === this.matchTypeFilter;
      });
    },
  },
  methods: {
    async fetchWithTimeout(url, options = {}, timeoutMs = 15000) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        return await fetch(url, { ...options, signal: controller.signal });
      } finally {
        clearTimeout(timer);
      }
    },
    escapeHtml(text) {
      return String(text ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
    },
    highlightHtml(text) {
      const raw = String(text ?? "");
      const q = this.queryText.trim();
      const escaped = this.escapeHtml(raw);
      if (!q) return escaped;
      const parts = Array.from(
        new Set(
          q
            .split(/\s+/)
            .map((item) => item.trim())
            .filter(Boolean)
            .sort((a, b) => b.length - a.length)
        )
      );
      if (!parts.length) return escaped;
      const safeQ = parts.map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|");
      try {
        return escaped.replace(new RegExp(safeQ, "gi"), (m) => `<mark>${m}</mark>`);
      } catch {
        return escaped;
      }
    },
    matchTypeLabel(matchType) {
      const labels = {
        title: "标题命中",
        filename: "文件名命中",
        summary: "摘要命中",
        caption: "图表标题命中",
        content: "正文命中",
        semantic: "语义命中",
      };
      return labels[matchType] || "内容命中";
    },
    async getJson(url) {
      const response = await this.fetchWithTimeout(url);
      if (!response.ok) {
        const raw = await response.text();
        try {
          const parsed = JSON.parse(raw);
          throw new Error(parsed.detail || parsed.message || raw || `${response.status}`);
        } catch {
          throw new Error(raw || `${response.status}`);
        }
      }
      return response.json();
    },
    async postJson(url, body) {
      const response = await this.fetchWithTimeout(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        const raw = await response.text();
        try {
          const parsed = JSON.parse(raw);
          throw new Error(parsed.detail || parsed.message || raw || `${response.status}`);
        } catch {
          throw new Error(raw || `${response.status}`);
        }
      }
      return response.json();
    },
    showToast(message) {
      this.toast = message;
      clearTimeout(this._toastTimer);
      this._toastTimer = setTimeout(() => {
        this.toast = "";
      }, 2200);
    },
    setMode(mode) {
      this.mode = mode;
      this.error = "";
      this.matchTypeFilter = "all";
    },
    closeOverview() {
      this.overviewModal = null;
    },
    async openOverview(docId) {
      if (!docId) return;
      this.loadingOverviewId = String(docId);
      try {
        const payload = await this.getJson(`${api.overview}/${docId}/overview`);
        this.overviewModal = payload || null;
        this.showToast("文档画像已加载");
      } catch (error) {
        this.error = error?.message || "读取文档画像失败";
        this.showToast("文档画像读取失败");
      } finally {
        this.loadingOverviewId = "";
      }
    },
    async submit() {
      if (!this.canSubmit) return;
      this.loading = true;
      this.error = "";
      this.overviewModal = null;
      try {
        if (this.mode === "qa") {
          this.answer = await this.postJson(api.qa, {
            query: this.queryText.trim(),
            excludeDevDocs: this.excludeDevDocs,
            topK: Number(this.topK || 5),
          });
          this.searchResult = null;
          this.showToast("问答已完成");
        } else {
          const params = new URLSearchParams({
            keyword: this.queryText.trim(),
            excludeDevDocs: String(this.excludeDevDocs),
            page: "1",
            pageSize: String(Number(this.topK || 5)),
          });
          this.searchResult = await this.getJson(`${api.search}?${params.toString()}`);
          this.answer = null;
          this.showToast("检索已完成");
        }
      } catch (error) {
        this.error = error?.name === "AbortError" ? "查询超时，请检查数据库初始化和索引状态" : (error?.message || "请求失败");
      } finally {
        this.loading = false;
      }
    },
  },
  template: `
    <div class="page-shell">
      <header class="topbar">
        <div>
          <div class="eyebrow">HmRAGCLI</div>
          <h1 class="title">知识查询</h1>
          <div class="subtitle">面向本地知识库的问答与检索入口。问答适合直接提问，检索适合快速翻找原文与证据。</div>
        </div>
        <div class="topbar-actions">
          <a class="btn btn-secondary" href="/ui/ops/index.html">打开运维面板</a>
        </div>
      </header>

      <nav class="tabs">
        <button class="tab" :class="{ active: mode === 'qa' }" @click="setMode('qa')">智能问答</button>
        <button class="tab" :class="{ active: mode === 'search' }" @click="setMode('search')">全文检索</button>
      </nav>

      <div class="layout">
        <section class="card">
          <h2>{{ mode === 'qa' ? '提问' : '检索' }}</h2>
          <p>{{ mode === 'qa' ? '输入一个自然语言问题，系统会返回答案、结构化摘要和引用。' : '输入关键词或主题，系统会返回文档级命中和片段证据。' }}</p>
          <textarea
            class="textarea"
            v-model="queryText"
            :placeholder="mode === 'qa' ? '例如：差旅报销的住宿标准是多少？' : '例如：研究生教育 数据 融合'"
          ></textarea>
          <div class="filters">
            <label class="check">
              <input type="checkbox" v-model="excludeDevDocs" />
              排除设计/开发文档
            </label>
            <label class="check">
              返回数量
              <select class="select" v-model="topK">
                <option :value="3">3</option>
                <option :value="5">5</option>
                <option :value="10">10</option>
                <option :value="20">20</option>
              </select>
            </label>
            <label v-if="mode === 'search'" class="check">
              命中类型
              <select class="select" v-model="matchTypeFilter">
                <option value="all">全部</option>
                <option value="title">标题命中</option>
                <option value="filename">文件名命中</option>
                <option value="summary">摘要命中</option>
                <option value="caption">图表标题命中</option>
                <option value="content">正文命中</option>
                <option value="semantic">语义命中</option>
              </select>
            </label>
          </div>
          <div class="toolbar" style="margin-top:16px;">
            <button class="btn btn-primary" :disabled="!canSubmit" @click="submit">
              {{ loading ? '处理中...' : (mode === 'qa' ? '开始问答' : '开始检索') }}
            </button>
          </div>
          <div v-if="error" class="error-box" style="margin-top:16px;">{{ error }}</div>
        </section>

        <section class="card answer-panel">
          <template v-if="loading">
            <div class="loading"><span class="dot"></span><span>{{ mode === 'qa' ? '正在生成答案...' : '正在检索结果...' }}</span></div>
          </template>

          <template v-else-if="mode === 'qa'">
            <template v-if="answer">
              <div class="answer-box">
                <div class="answer-meta" style="margin-bottom:12px;">
                  <span class="pill info">{{ answer.queryType || answer.query_type }}</span>
                  <span class="muted">引用 {{ answer.citations?.length || 0 }} 条</span>
                </div>
                <div class="answer-text">{{ answer.answer }}</div>
              </div>

              <div v-if="answer.docOverview || answer.doc_overview" class="struct-box">
                <div class="section-title">文档画像</div>
                <div class="muted" style="margin-bottom:8px;">{{ (answer.docOverview || answer.doc_overview).summary || '-' }}</div>
                <div class="search-meta">
                  <span class="muted" v-if="(answer.docOverview || answer.doc_overview).keyTopics?.length">主题：{{ (answer.docOverview || answer.doc_overview).keyTopics.join('、') }}</span>
                  <span class="muted" v-if="(answer.docOverview || answer.doc_overview).keywords?.length">关键词：{{ (answer.docOverview || answer.doc_overview).keywords.join('、') }}</span>
                </div>
              </div>

              <div v-if="structuredEntries.length || normalizedStructuredAnswer?.summaryPoints?.length || normalizedStructuredAnswer?.summary_points?.length" class="struct-box">
                <div class="section-title">结构化结果</div>
                <div v-if="structuredEntries.length" class="kv-grid">
                  <div v-for="item in structuredEntries" :key="item.key" class="kv-item">
                    <div class="kv-key">{{ item.label }}</div>
                    <div class="kv-value">{{ item.value }}</div>
                  </div>
                </div>
                <div v-if="normalizedStructuredAnswer?.summaryPoints?.length || normalizedStructuredAnswer?.summary_points?.length" style="margin-top:14px;">
                  <div class="kv-key" style="margin-bottom:8px;">摘要要点</div>
                  <div class="citation-list">
                    <div v-for="(point, idx) in (normalizedStructuredAnswer.summaryPoints || normalizedStructuredAnswer.summary_points || [])" :key="idx" class="citation-item">{{ point }}</div>
                  </div>
                </div>
              </div>

              <div>
                <div class="section-title">引用</div>
                <div class="citation-list">
                  <div v-for="(citation, idx) in answer.citations" :key="idx" class="citation-item">
                    <div><strong>{{ citation.title || citation.docId || citation.doc_id }}</strong></div>
                    <div class="muted">原始文件：{{ citation.sourceFilename || citation.source_filename || citation.title || '-' }}</div>
                    <div class="muted">原始路径：{{ citation.sourceFile || citation.source_file }}</div>
                    <div class="muted" v-if="(citation.relativePath || citation.relative_path) && (citation.relativePath || citation.relative_path) !== (citation.sourceFile || citation.source_file)">相对路径：{{ citation.relativePath || citation.relative_path }}</div>
                    <div class="muted">docId={{ citation.docId || citation.doc_id }}</div>
                    <div class="muted" v-if="citation.sourceSpan || citation.source_span">位置：{{ citation.sourceSpan || citation.source_span }}</div>
                    <div class="muted" v-if="citation.pageNo || citation.page_no">页码：{{ citation.pageNo || citation.page_no }}</div>
                  </div>
                </div>
              </div>
            </template>
            <div v-else class="empty">还没有问答结果。输入问题后点击“开始问答”。</div>
          </template>

          <template v-else>
            <template v-if="searchResult">
              <div class="search-meta" style="margin-bottom:14px;">
                <span class="pill info">文档命中 {{ normalizedDocHits.length || 0 }} 条</span>
                <span class="pill success">证据片段 {{ filteredSearchItems.length || 0 }} 条</span>
              </div>

              <div v-if="normalizedDocHits.length">
                <div class="section-title">文档级结果（第一跳）</div>
                <div class="search-list">
                  <article v-for="doc in normalizedDocHits" :key="doc.docId || doc.doc_id" class="search-item">
                    <div class="search-item-header">
                      <div>
                        <h3 class="search-title" v-html="highlightHtml(doc.docTitle || doc.doc_title || doc.sourceFilename || doc.source_filename || '未命名文档')"></h3>
                        <div class="muted">score={{ Number(doc.score || 0).toFixed(3) }} / 命中片段 {{ doc.hitCount || doc.hit_count || 0 }}</div>
                      </div>
                      <div class="topbar-actions">
                        <button class="btn btn-secondary" :disabled="loadingOverviewId === String(doc.docId || doc.doc_id)" @click="openOverview(doc.docId || doc.doc_id)">
                          {{ loadingOverviewId === String(doc.docId || doc.doc_id) ? '读取中...' : '查看文档画像' }}
                        </button>
                      </div>
                    </div>
                    <div class="search-content" v-html="highlightHtml(doc.overview?.summary || doc.overview?.summary || '-')"></div>
                    <div class="search-meta" style="margin-top:10px;">
                      <span class="muted" v-html="'原始文件：' + highlightHtml(doc.sourceFilename || doc.source_filename || '-')"></span>
                      <span class="muted" v-html="'原始路径：' + highlightHtml(doc.sourceFile || doc.source_file || '-')"></span>
                      <span v-if="(doc.relativePath || doc.relative_path) && (doc.relativePath || doc.relative_path) !== (doc.sourceFile || doc.source_file)" class="muted" v-html="'相对路径：' + highlightHtml(doc.relativePath || doc.relative_path)"></span>
                      <span class="muted" v-if="doc.overview?.keyTopics?.length">主题：{{ doc.overview.keyTopics.join('、') }}</span>
                    </div>
                  </article>
                </div>
              </div>

              <div style="margin-top:14px;">
                <div class="section-title">证据片段（第二跳）</div>
                <div class="search-list">
                  <article v-for="item in filteredSearchItems" :key="(item.unitId || item.unit_id || item.chunkId || item.chunk_id || item.docId || item.doc_id)" class="search-item">
                    <div class="search-item-header">
                      <div>
                        <h3 class="search-title" v-html="highlightHtml(item.title || item.docTitle || item.doc_title || '未命名结果')"></h3>
                        <div class="muted">{{ item.docTitle || item.doc_title || '-' }} / {{ item.docType || item.doc_type || '-' }} / score={{ Number(item.score || 0).toFixed(3) }}</div>
                      </div>
                      <div class="topbar-actions">
                        <span class="pill" :class="(item.kind || '') === 'knowledge_unit' ? 'success' : 'info'">{{ item.kind }}</span>
                        <span class="pill info">{{ matchTypeLabel(item.matchType || item.match_type) }}</span>
                        <span class="pill" :class="(item.docDomain || item.doc_domain) === 'development' ? 'warn' : 'success'">{{ item.docDomain || item.doc_domain || '-' }}</span>
                      </div>
                    </div>
                    <div class="search-content" v-html="highlightHtml(item.snippet || item.content)"></div>
                    <div class="search-meta" style="margin-top:10px;">
                      <span class="muted" v-html="'原始文件：' + highlightHtml(item.sourceFilename || item.source_filename || item.docTitle || item.doc_title || '-')"></span>
                      <span class="muted" v-html="'原始路径：' + highlightHtml(item.sourceFile || item.source_file || '-')"></span>
                      <span v-if="(item.relativePath || item.relative_path) && (item.relativePath || item.relative_path) !== (item.sourceFile || item.source_file)" class="muted" v-html="'相对路径：' + highlightHtml(item.relativePath || item.relative_path)"></span>
                      <span v-if="item.subject" class="muted" v-html="'主题：' + highlightHtml(item.subject)"></span>
                      <span v-if="item.indicator" class="muted" v-html="'指标：' + highlightHtml(item.indicator)"></span>
                      <span v-if="item.sourceSpan || item.source_span" class="muted" v-html="'位置：' + highlightHtml(item.sourceSpan || item.source_span)"></span>
                      <span v-if="item.pageNo || item.page_no" class="muted">页码：{{ item.pageNo || item.page_no }}</span>
                    </div>
                  </article>
                </div>
              </div>

              <div v-if="normalizedEvidenceHits.length && !filteredSearchItems.length" class="empty">当前命中类型筛选下没有结果。</div>
            </template>
            <div v-else class="empty">还没有检索结果。输入关键词后点击“开始检索”。</div>
          </template>
        </section>
      </div>

      <div v-if="overviewModal" class="card" style="margin-top:18px;">
        <div class="search-item-header">
          <h3 class="search-title">文档画像详情</h3>
          <button class="btn btn-secondary" @click="closeOverview">关闭</button>
        </div>
        <div class="search-meta" style="margin-bottom:8px;">
          <span class="muted">文档：{{ overviewModal.docTitle || overviewModal.doc_title || '-' }}</span>
          <span class="muted">文件：{{ overviewModal.sourceFilename || overviewModal.source_filename || '-' }}</span>
        </div>
        <div class="search-content">{{ overviewModal.overview?.summary || '-' }}</div>
        <div class="search-meta" style="margin-top:10px;">
          <span class="muted" v-if="overviewModal.overview?.sections?.length">章节：{{ overviewModal.overview.sections.join('、') }}</span>
          <span class="muted" v-if="overviewModal.overview?.keyTopics?.length">主题：{{ overviewModal.overview.keyTopics.join('、') }}</span>
          <span class="muted" v-if="overviewModal.overview?.keywords?.length">关键词：{{ overviewModal.overview.keywords.join('、') }}</span>
          <span class="muted" v-if="overviewModal.overview?.entities?.length">实体：{{ overviewModal.overview.entities.join('、') }}</span>
          <span class="muted" v-if="overviewModal.overview?.timeRange">时间范围：{{ overviewModal.overview.timeRange }}</span>
        </div>
        <div v-if="overviewModal.overview?.conclusions?.length" style="margin-top:10px;">
          <div class="section-title">结论要点</div>
          <div class="citation-list">
            <div v-for="(point, idx) in overviewModal.overview.conclusions" :key="idx" class="citation-item">{{ point }}</div>
          </div>
        </div>
      </div>

      <div class="toast" :class="{ visible: !!toast }">{{ toast }}</div>
    </div>
  `,
}).mount("#app");
