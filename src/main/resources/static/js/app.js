// Minimal vanilla JS client for the Bank Agent demo. No build step, no external libraries.
(function () {
  'use strict';

  const EXAMPLES = [
    '我现在的活期账户余额是多少？',
    '列出我最近的五笔交易。',
    '上个月我在哪类消费上花钱最多？一共花了多少？',
    '把我的账户余额和最近五笔交易一起告诉我。',
    '向张三转账 500 元。',
    '取消刚才的转账。',
    '再给他转 300 元。',
    '向一个未登记的陌生收款人转账 999999 元。',
    '这笔转账失败的原因是什么？'
  ];

  const STATUS_LABELS = {
    PENDING_CONFIRMATION: '待确认',
    EXECUTED: '已执行（模拟）',
    CANCELLED: '已取消',
    EXPIRED: '已过期',
    FAILED: '失败'
  };

  const AGENT_LABELS = { ACCOUNT: 'Account Agent', TRANSACTIONS: 'Transactions Agent', PAYMENTS: 'Payments Agent' };

  const state = { conversationId: null, busy: false, drafts: new Map() };

  const $ = (id) => document.getElementById(id);
  const messagesEl = $('messages');

  // ---- Safe rendering helpers -------------------------------------------------------------

  function escapeHtml(text) {
    return String(text == null ? '' : text)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function inline(text) {
    // Input is already HTML-escaped; only a tiny markdown subset is converted.
    return text
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/`([^`]+)`/g, '<code>$1</code>');
  }

  function renderMarkdown(raw) {
    const lines = escapeHtml(raw).split(/\r?\n/);
    let html = '';
    let list = null;
    const closeList = () => { if (list) { html += '</' + list + '>'; list = null; } };
    for (const line of lines) {
      const trimmed = line.trim();
      let m;
      if ((m = trimmed.match(/^[-*•]\s+(.*)$/))) {
        if (list !== 'ul') { closeList(); html += '<ul>'; list = 'ul'; }
        html += '<li>' + inline(m[1]) + '</li>';
      } else if ((m = trimmed.match(/^\d+[.)、]\s*(.*)$/))) {
        if (list !== 'ol') { closeList(); html += '<ol>'; list = 'ol'; }
        html += '<li>' + inline(m[1]) + '</li>';
      } else if (trimmed === '') {
        closeList();
      } else {
        closeList();
        const heading = trimmed.match(/^#{1,6}\s+(.*)$/);
        html += '<p>' + (heading ? '<strong>' + inline(heading[1]) + '</strong>' : inline(trimmed)) + '</p>';
      }
    }
    closeList();
    return html;
  }

  function money(value, currency) {
    if (value == null) return '-';
    const n = Number(value);
    const formatted = Number.isFinite(n)
      ? n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
      : escapeHtml(value);
    return formatted + ' ' + (currency === 'CNY' || !currency ? '元' : escapeHtml(currency));
  }

  function fmtTime(value) {
    if (!value) return '-';
    return escapeHtml(String(value).replace('T', ' ').substring(0, 19));
  }

  // ---- Chat messages ----------------------------------------------------------------------

  function addMessage(role, html, meta) {
    const wrap = document.createElement('div');
    wrap.className = 'msg msg-' + role;
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.innerHTML = html;
    wrap.appendChild(bubble);
    if (meta) {
      const m = document.createElement('div');
      m.className = 'meta';
      m.innerHTML = meta;
      wrap.appendChild(m);
    }
    messagesEl.appendChild(wrap);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return wrap;
  }

  function setBusy(busy) {
    state.busy = busy;
    $('typing').classList.toggle('hidden', !busy);
    $('sendBtn').disabled = busy;
  }

  async function api(method, url, body) {
    const opts = { method, headers: { 'Accept': 'application/json' } };
    if (body !== undefined) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    let res;
    try {
      res = await fetch(url, opts);
    } catch (e) {
      throw { code: 'NETWORK_ERROR', message: '无法连接本地服务，请确认应用仍在运行。' };
    }
    let data = null;
    try { data = await res.json(); } catch (e) { data = null; }
    const traceId = res.headers.get('X-Bank-Trace-Id');
    if (!res.ok) {
      const err = data && data.code ? data : { code: 'HTTP_' + res.status, message: '请求失败（HTTP ' + res.status + '）。' };
      if (traceId) err.traceId = traceId;
      throw err;
    }
    if (traceId && data && typeof data === 'object') data.traceId = data.traceId || traceId;
    return data;
  }

  function traceNote(traceId) {
    return traceId ? ' <span class="muted">（Trace：<code>' + escapeHtml(traceId) + '</code>）</span>' : '';
  }

  function errorText(err) {
    const map = {
      LLM_NOT_CONFIGURED: 'OpenAI 尚未配置：请在本机私有配置文件中设置 OPENAI_API_KEY 后重启应用。',
      LLM_UPSTREAM_ERROR: '调用 OpenAI 服务失败，请稍后重试。',
      CONVERSATION_BUSY: '上一条消息仍在处理中，请稍候。',
      CONVERSATION_NOT_FOUND: '会话已失效，请新建会话。',
      LOCAL_ACCESS_ONLY: '该操作仅允许在本机执行。'
    };
    const control = {
      CONTROL_DENIED: 'Agent Control 安全策略拒绝了该操作（未执行，未扣款）：',
      CONTROL_STEER_REQUIRES_USER_ACTION: 'Agent Control 要求调整后才能执行（未执行，未扣款）：',
      CONTROL_UNAVAILABLE: '安全检查服务暂时不可用，操作未执行（fail-closed，未扣款）：'
    };
    if (control[err.code]) {
      return control[err.code] + (err.message || '') + ' [' + err.code + ']';
    }
    const base = map[err.code] || err.message || '请求失败。';
    return base + ' [' + (err.code || 'ERROR') + ']';
  }

  async function sendMessage(text) {
    const message = text.trim();
    if (!message || state.busy) return;
    addMessage('user', escapeHtml(message).replace(/\n/g, '<br>'));
    $('messageInput').value = '';
    setBusy(true);
    try {
      const data = await api('POST', '/api/chat', { conversationId: state.conversationId, message });
      state.conversationId = data.conversationId;
      $('convId').textContent = data.conversationId;
      const agents = (data.selectedAgents || []).map((a) => AGENT_LABELS[a] || a).join(' + ');
      let meta = (agents ? escapeHtml(agents) + ' · ' : '') + (data.durationMs / 1000).toFixed(1) + 's';
      // Fail-open read steps: the answer was produced WITHOUT a safety check - say so, never imply it passed.
      if ((data.controlDecisions || []).some((c) => c.verdict === 'PROCEED_UNCHECKED')) {
        meta += ' · <span class="warn">⚠ Agent Control 服务不可用：部分只读步骤未经安全检查（fail-open）</span>';
      }
      const node = addMessage('assistant', renderMarkdown(data.answer || ''), meta);
      applyDraftEvents(data.draftEvents || []);
      if (data.pendingDraft && data.pendingDraft.status === 'PENDING_CONFIRMATION') {
        node.querySelector('.bubble').appendChild(renderDraftCard(data.pendingDraft));
      }
      renderDebug(data);
      if ((data.executedTools || []).length) refreshProfile();
    } catch (err) {
      addMessage('error', escapeHtml(errorText(err)));
    } finally {
      setBusy(false);
      $('messageInput').focus();
    }
  }

  // ---- Transfer draft cards ---------------------------------------------------------------

  function renderDraftCard(draft) {
    const card = document.createElement('div');
    card.className = 'draft-card';
    card.dataset.draftId = draft.draftId;
    card.innerHTML =
      '<h3>转账草稿 · 需要您确认 <span class="draft-status"></span></h3>' +
      '<dl>' +
      '<dt>收款人</dt><dd>' + escapeHtml(draft.beneficiaryName) + '（' + escapeHtml(draft.beneficiaryMaskedAccount || '') + '）</dd>' +
      '<dt>转出账户</dt><dd>' + escapeHtml(draft.fromAccountMasked || draft.fromAccountId) + '</dd>' +
      '<dt>金额</dt><dd class="amount">' + money(draft.amount, draft.currency) + '</dd>' +
      (draft.memo ? '<dt>附言</dt><dd>' + escapeHtml(draft.memo) + '</dd>' : '') +
      '<dt>有效期至</dt><dd>' + fmtTime(draft.expiresAt) + '</dd>' +
      '<dt>草稿编号</dt><dd><code>' + escapeHtml(draft.draftId) + '</code></dd>' +
      '</dl>' +
      '<div class="buttons">' +
      '<button type="button" class="btn btn-confirm">确认转账</button>' +
      '<button type="button" class="btn btn-cancel">取消</button>' +
      '</div>' +
      '<div class="draft-result"></div>';
    card.querySelector('.btn-confirm').addEventListener('click', () => draftAction(draft.draftId, 'confirm'));
    card.querySelector('.btn-cancel').addEventListener('click', () => draftAction(draft.draftId, 'cancel'));
    state.drafts.set(draft.draftId, card);
    setCardStatus(card, draft.status);
    return card;
  }

  function setCardStatus(card, status, resultHtml) {
    const badge = card.querySelector('.draft-status');
    badge.className = 'draft-status ' + status;
    badge.textContent = STATUS_LABELS[status] || status;
    const pending = status === 'PENDING_CONFIRMATION';
    card.querySelectorAll('.buttons button').forEach((b) => { b.disabled = !pending; });
    if (!pending) card.querySelector('.buttons').classList.add('hidden');
    if (resultHtml !== undefined) card.querySelector('.draft-result').innerHTML = resultHtml;
  }

  function applyDraftEvents(events) {
    for (const ev of events) {
      if (!ev.draftId) continue;
      const card = state.drafts.get(ev.draftId);
      if (!card) continue;
      if (ev.event === 'SUPERSEDED') {
        setCardStatus(card, 'CANCELLED', '<span class="muted">已被新的转账草稿替代，此草稿作废。</span>');
      } else if (ev.event === 'CANCELLED') {
        setCardStatus(card, ev.status || 'CANCELLED', '<span class="muted">已根据您的对话指令取消，未发生扣款。</span>');
      } else if (ev.event === 'WITHDRAWN') {
        setCardStatus(card, 'CANCELLED', '<span class="fail">Agent Control 拦截了草稿结果，草稿已撤回，未发生扣款。</span>');
      }
    }
  }

  async function draftAction(draftId, action) {
    const card = state.drafts.get(draftId);
    if (!card || !state.conversationId) return;
    card.querySelectorAll('.buttons button').forEach((b) => { b.disabled = true; });
    try {
      const url = '/api/payments/drafts/' + encodeURIComponent(draftId) + '/' + action;
      const data = await api('POST', url, { conversationId: state.conversationId });
      if (action === 'confirm') {
        const d = data.draft;
        const text = data.replayed
          ? '该转账此前已执行，本次未重复扣款。'
          : '转账已执行（模拟）：向 ' + escapeHtml(d.beneficiaryName) + ' 转出 ' + money(d.amount, d.currency) +
            '，转出账户余额 ' + money(data.balanceAfter, d.currency) + '。';
        setCardStatus(card, d.status, '<span class="ok">' + text + '</span>' + traceNote(data.traceId));
        addMessage('system', '✅ ' + text);
      } else {
        setCardStatus(card, data.status, '<span class="muted">转账已取消，未发生扣款。</span>' + traceNote(data.traceId));
        addMessage('system', '已取消转账草稿：' + escapeHtml(data.beneficiaryName) + ' ' + money(data.amount, data.currency) + '，未发生扣款。');
      }
      refreshProfile();
    } catch (err) {
      const terminal = { DRAFT_EXPIRED: 'EXPIRED', DRAFT_CANCELLED: 'CANCELLED', DRAFT_FAILED: 'FAILED', DRAFT_ALREADY_EXECUTED: 'EXECUTED',
        INSUFFICIENT_FUNDS: 'FAILED' }[err.code];
      if (terminal) {
        setCardStatus(card, terminal, '<span class="fail">' + escapeHtml(errorText(err)) + '</span>' + traceNote(err.traceId));
      } else {
        card.querySelectorAll('.buttons button').forEach((b) => { b.disabled = false; });
        card.querySelector('.draft-result').innerHTML = '<span class="fail">' + escapeHtml(errorText(err)) + '</span>' + traceNote(err.traceId);
      }
      refreshProfile();
    }
  }

  // ---- Debug panel (values come from the backend execution trace) -------------------------

  function renderDebug(data) {
    const steps = data.agentSteps || [];
    const tools = data.toolCalls || [];
    const llm = data.llmCalls || [];
    let html = '<div><strong>Intent：</strong>' + escapeHtml(data.intent || '-') +
      ' · <strong>总耗时：</strong>' + data.durationMs + ' ms · <strong>模型：</strong>' + escapeHtml(data.model || '-') + '</div>';
    html += '<div style="margin-top:4px"><strong>选中 Agent：</strong>' +
      ((data.selectedAgents || []).map((a) => '<span class="tag">' + escapeHtml(AGENT_LABELS[a] || a) + '</span>').join('') || '<span class="muted">无（Supervisor 直接回复）</span>') + '</div>';
    html += '<h4>Agent 执行步骤</h4>';
    html += steps.length ? '<table><tr><th>Agent</th><th>状态</th><th>耗时</th></tr>' + steps.map((s) =>
      '<tr><td>' + escapeHtml(AGENT_LABELS[s.agent] || s.agent) + '<div class="args">' + escapeHtml(s.task) + '</div></td><td class="' +
      (s.status === 'OK' ? 'ok' : 'fail') + '">' + escapeHtml(s.status) + '</td><td>' + s.durationMs + ' ms</td></tr>').join('') + '</table>'
      : '<p class="muted small">无</p>';
    html += '<h4>Tool 调用</h4>';
    html += tools.length ? '<table><tr><th>Tool</th><th>结果</th><th>耗时</th></tr>' + tools.map((t) =>
      '<tr><td>' + escapeHtml(t.tool) + '<div class="args">' + escapeHtml(t.arguments || '') + '</div></td><td class="' +
      (t.success ? 'ok' : 'fail') + '">' + (t.success ? '成功' : '失败') + '</td><td>' + t.durationMs + ' ms</td></tr>').join('') + '</table>'
      : '<p class="muted small">无</p>';
    html += '<h4>LLM 调用</h4>';
    html += llm.length ? '<table><tr><th>调用方</th><th>Tokens 入/出</th><th>耗时</th></tr>' + llm.map((c) =>
      '<tr><td>' + escapeHtml(c.caller) + '<div class="args">' + escapeHtml(c.finishReason || '') + '</div></td><td>' +
      (c.inputTokens == null ? '-' : c.inputTokens) + ' / ' + (c.outputTokens == null ? '-' : c.outputTokens) + '</td><td class="' +
      (c.success ? '' : 'fail') + '">' + c.durationMs + ' ms</td></tr>').join('') + '</table>'
      : '<p class="muted small">无</p>';
    if ((data.draftEvents || []).length) {
      html += '<h4>转账草稿事件</h4><table>' + data.draftEvents.map((e) =>
        '<tr><td>' + escapeHtml(e.event) + '</td><td>' + escapeHtml(e.status || '') + '</td><td class="args">' + escapeHtml(e.draftId || '') + '</td></tr>').join('') + '</table>';
    }
    const controls = data.controlDecisions || [];
    if (controls.length) {
      const cls = { PROCEED: 'ok', PROCEED_UNCHECKED: 'warn', DENY: 'fail', STEER: 'warn', BLOCKED_UNAVAILABLE: 'fail' };
      html += '<h4>Agent Control 检查</h4><table><tr><th>检查时机 / 步骤</th><th>结果</th><th>耗时</th></tr>' + controls.map((c) =>
        '<tr><td>' + escapeHtml(c.stage) + ' · ' + escapeHtml(c.stepName) + '<div class="args">' +
        escapeHtml((c.controlName ? c.controlName + ' · ' : '') + (c.reason || '')) + '</div></td><td class="' +
        (cls[c.verdict] || '') + '">' + escapeHtml(c.verdict) + (c.action ? '<div class="args">' + escapeHtml(c.action) + '</div>' : '') +
        '</td><td>' + c.latencyMs + ' ms</td></tr>').join('') + '</table>';
    }
    if (data.traceId) {
      html += '<h4>Trace</h4><div class="args">OTel trace_id：<code>' + escapeHtml(data.traceId) + '</code></div>' +
        '<div class="args">AO Trace ID：<code>' + escapeHtml(data.aoTraceId || '') + '</code>（AO Console 中 External Id = OTel trace_id）</div>';
    }
    $('debugPanel').innerHTML = html;
  }

  // ---- Sidebar ----------------------------------------------------------------------------

  async function refreshProfile() {
    try {
      const data = await api('GET', '/api/demo/profile');
      const s = data.summary;
      $('profile').innerHTML = '<div class="muted small">' + escapeHtml(s.customerName) + '</div>' +
        s.accounts.map((a) => '<div class="acct"><div class="name">' + escapeHtml(a.typeLabel) + ' <span class="muted small">' +
          escapeHtml(a.maskedNumber) + '</span></div><div class="bal">' + money(a.balance, a.currency) + '</div></div>').join('');
      $('beneficiaries').innerHTML = data.beneficiaries.map((b) => '<li><strong>' + escapeHtml(b.name) + '</strong> <span class="muted small">' +
        escapeHtml(b.bankName) + ' ' + escapeHtml(b.maskedAccount) + '</span></li>').join('');
    } catch (err) {
      $('profile').innerHTML = '<p class="fail small">' + escapeHtml(errorText(err)) + '</p>';
    }
  }

  function newConversation() {
    state.conversationId = null;
    state.drafts.clear();
    $('convId').textContent = '（新会话）';
    messagesEl.innerHTML = '';
    addMessage('assistant', '已开始新的会话。请问需要什么帮助？');
    $('debugPanel').innerHTML = '<p class="muted">发送消息后显示 Agent 路由、Tool 调用与耗时。</p>';
  }

  async function resetDemo() {
    if (!window.confirm('确定要重置演示数据吗？余额、交易和转账草稿将恢复初始状态，所有会话将被清空。')) return;
    try {
      await api('POST', '/api/demo/reset');
      newConversation();
      addMessage('system', '演示数据已重置为初始状态。');
      refreshProfile();
    } catch (err) {
      addMessage('error', escapeHtml(errorText(err)));
    }
  }

  // ---- Wiring -----------------------------------------------------------------------------

  function init() {
    const examples = $('examples');
    EXAMPLES.forEach((q, i) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'chip';
      b.textContent = (i + 1) + '. ' + q;
      b.addEventListener('click', () => { $('messageInput').value = q; $('messageInput').focus(); });
      examples.appendChild(b);
    });
    $('chatForm').addEventListener('submit', (e) => { e.preventDefault(); sendMessage($('messageInput').value); });
    $('messageInput').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); sendMessage($('messageInput').value); }
    });
    $('newConversation').addEventListener('click', newConversation);
    $('resetDemo').addEventListener('click', resetDemo);
    refreshProfile();
  }

  document.addEventListener('DOMContentLoaded', init);
})();
