import { srpLogin, loadSrpParams, generateVerifierForRotate } from './srp-client.js';

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

const state = {
  session: null,
  currentView: 'versions',
  uploadSession: null,
};

async function api(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: { ...(options.headers || {}), 'Accept': 'application/json' },
    credentials: 'same-origin',
  });
  if (res.status === 204) return null;
  const text = await res.text();
  let body = null;
  if (text) {
    try { body = JSON.parse(text); }
    catch { body = { raw: text }; }
  }
  if (!res.ok) {
    const err = new Error(body?.message || `HTTP ${res.status}`);
    err.status = res.status;
    err.body = body;
    throw err;
  }
  return body;
}

function showView(name) {
  state.currentView = name;
  $$('.view').forEach((v) => v.classList.toggle('active', v.id === `view-${name}`));
  $$('.nav-btn').forEach((b) => b.classList.toggle('active', b.dataset.view === name));
}

function fmtSize(bytes) {
  if (!bytes) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GiB`;
}

function fmtTime(s) {
  if (!s) return '-';
  return s.replace('T', ' ').replace(/\.\d+Z$/, 'Z');
}

function shaShort(sha) {
  if (!sha) return '-';
  return sha.length > 12 ? `${sha.slice(0, 8)}…${sha.slice(-4)}` : sha;
}

function escapeHtml(s) {
  if (s === null || s === undefined) return '';
  return String(s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

async function checkSession() {
  try {
    const body = await api('/admin/session');
    state.session = body;
    return true;
  } catch (err) {
    if (err.status === 401) return false;
    console.error('session check failed:', err);
    return false;
  }
}

async function handleLoginSubmit(e) {
  e.preventDefault();
  const form = e.target;
  const passwordInput = form.elements.password;
  const password = passwordInput.value;
  passwordInput.value = ''; // clear input immediately
  const errEl = $('#loginError');
  errEl.hidden = true;
  const submitBtn = form.querySelector('button[type=submit]');
  submitBtn.disabled = true;
  const originalText = submitBtn.textContent;
  submitBtn.textContent = '正在握手...';
  try {
    await srpLogin(password);
    await enterConsole();
  } catch (err) {
    errEl.textContent = err.message;
    errEl.hidden = false;
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = originalText;
  }
}

async function enterConsole() {
  $('#nav').hidden = false;
  showView('versions');
  await loadVersions();
}

async function logout() {
  await fetch('/admin/logout', { method: 'POST', credentials: 'same-origin' });
  state.session = null;
  $('#nav').hidden = true;
  showView('login');
}

async function loadVersions() {
  const platform = $('#platformFilter').value;
  const query = platform ? `?platform=${encodeURIComponent(platform)}` : '';
  const body = await api(`/admin/versions${query}`);
  const tbody = $('#versionsBody');
  if (!body.versions || body.versions.length === 0) {
    tbody.innerHTML = '<tr><td colspan="7" class="empty">还没有发布过版本</td></tr>';
    return;
  }
  tbody.innerHTML = body.versions.map((v) => `
    <tr>
      <td class="mono">${escapeHtml(v.platform)}</td>
      <td class="mono">${escapeHtml(v.version)}</td>
      <td>${fmtSize(v.file_size)}</td>
      <td class="sha-cell" title="${escapeHtml(v.file_sha256)}">${escapeHtml(shaShort(v.file_sha256))}</td>
      <td>${v.is_latest ? '<span class="badge badge-latest">LATEST</span>' : '<span class="badge badge-old">历史</span>'}</td>
      <td class="mono">${escapeHtml(fmtTime(v.created_at))}</td>
      <td>
        ${v.is_latest ? '' : `<button class="btn btn-danger btn-sm" data-action="delete" data-id="${escapeHtml(v.id)}">删除</button>`}
        <button class="btn btn-secondary btn-sm" data-action="edit" data-id="${escapeHtml(v.id)}">改 notes</button>
        ${v.is_latest ? '' : `<button class="btn btn-secondary btn-sm" data-action="publish" data-id="${escapeHtml(v.id)}">设为 latest</button>`}
      </td>
    </tr>
  `).join('');
}

async function loadAudit() {
  const limit = Number.parseInt($('#auditLimit').value, 10) || 100;
  const body = await api(`/admin/audit?limit=${limit}`);
  const tbody = $('#auditBody');
  if (!body.audit || body.audit.length === 0) {
    tbody.innerHTML = '<tr><td colspan="6" class="empty">还没有审计记录</td></tr>';
    return;
  }
  tbody.innerHTML = body.audit.map((a) => `
    <tr>
      <td class="mono">${escapeHtml(fmtTime(a.ts))}</td>
      <td class="mono">${escapeHtml(a.actor)}</td>
      <td class="mono">${escapeHtml(a.action)}</td>
      <td class="mono">${escapeHtml(a.platform || '')}</td>
      <td class="mono">${escapeHtml(a.version || '')}</td>
      <td>${escapeHtml(a.detail || '')}</td>
    </tr>
  `).join('');
}

async function handleVersionsClick(e) {
  const btn = e.target.closest('button[data-action]');
  if (!btn) return;
  const id = btn.dataset.id;
  if (btn.dataset.action === 'delete') {
    if (!confirm('删除该历史版本？此操作不可撤销。')) return;
    const res = await fetch(`/admin/versions/${id}`, { method: 'DELETE', credentials: 'same-origin' });
    if (res.status !== 204) {
      const body = await res.json().catch(() => ({}));
      alert(`删除失败：${body.message || res.status}`);
    }
    await loadVersions();
  } else if (btn.dataset.action === 'publish') {
    const res = await fetch(`/admin/versions/${id}/publish`, { method: 'POST', credentials: 'same-origin' });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      alert(`发布失败：${body.message || res.status}`);
      return;
    }
    await loadVersions();
  } else if (btn.dataset.action === 'edit') {
    const notes = prompt('新的 notes 内容：');
    if (notes === null) return;
    const res = await fetch(`/admin/versions/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ notes }),
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      alert(`修改失败：${body.message || res.status}`);
    }
    await loadVersions();
  }
}

function signatureNameFor(fileName) {
  return `${fileName}.sig`;
}

function fileNameFromPath(p) {
  const idx = p.lastIndexOf(/[\\/]/);
  return p.slice(idx + 1);
}

async function sha256OfFile(file) {
  const buf = await file.arrayBuffer();
  const hash = await crypto.subtle.digest('SHA-256', buf);
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

function setProgress(fillId, textId, percent) {
  $(`#${fillId}`).style.width = `${percent}%`;
  $(`#${textId}`).textContent = `${percent}%`;
}

async function uploadFile(file, kind, uploadId, onProgress) {
  const chunkSize = state.uploadSession.package_chunk_size;
  let offset = 0;
  while (offset < file.size) {
    const end = Math.min(offset + chunkSize, file.size);
    const chunk = file.slice(offset, end);
    const res = await fetch(`/admin/uploads/${uploadId}/${kind}`, {
      method: 'PATCH',
      headers: { 'upload-offset': String(offset) },
      credentials: 'same-origin',
      body: chunk,
    });
    if (res.status !== 204) {
      const body = await res.json().catch(() => ({}));
      throw new Error(`分片上传失败 (${res.status}): ${body.message || 'unknown'}`);
    }
    offset = Number(res.headers.get('upload-offset'));
    onProgress(offset, file.size);
  }
}

async function handleUploadSubmit(e) {
  e.preventDefault();
  const form = e.target;
  const platform = form.elements.platform.value;
  const version = form.elements.version.value;
  const notes = form.elements.notes.value;
  const packageFile = $('#packageFile').files[0];
  const signatureFile = $('#signatureFile').files[0];

  if (!packageFile || !signatureFile) {
    alert('请选择安装包和签名文件');
    return;
  }
  if (signatureFile.name !== signatureNameFor(packageFile.name)) {
    alert(`签名文件名必须为 ${signatureNameFor(packageFile.name)}（当前为 ${signatureFile.name}）`);
    return;
  }

  const startBtn = $('#btnStartUpload');
  startBtn.disabled = true;
  $('#uploadProgress').hidden = false;
  $('#uploadResult').hidden = true;
  $('#uploadStage').textContent = '正在创建上传 session...';
  setProgress('packageFill', 'packageProgress', 0);
  setProgress('signatureFill', 'signatureProgress', 0);

  try {
    const sha256 = await sha256OfFile(packageFile);
    const createRes = await fetch('/admin/uploads', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({
        platform,
        version,
        file_name: packageFile.name,
        file_size: packageFile.size,
        signature_name: signatureFile.name,
        signature_size: signatureFile.size,
        sha256,
      }),
    });
    if (createRes.status !== 201) {
      const body = await createRes.json().catch(() => ({}));
      throw new Error(`创建上传 session 失败：${body.message || createRes.status}`);
    }
    state.uploadSession = await createRes.json();
    $('#uploadStage').textContent = '上传安装包...';
    await uploadFile(packageFile, 'package', state.uploadSession.upload_id, (offset, total) => {
      setProgress('packageFill', 'packageProgress', Math.round((offset / total) * 100));
    });
    $('#uploadStage').textContent = '上传签名文件...';
    await uploadFile(signatureFile, 'signature', state.uploadSession.upload_id, (offset, total) => {
      setProgress('signatureFill', 'signatureProgress', Math.round((offset / total) * 100));
    });
    $('#uploadStage').textContent = '完成中...';
    const completeRes = await fetch(`/admin/uploads/${state.uploadSession.upload_id}/complete`, {
      method: 'POST',
      credentials: 'same-origin',
    });
    if (completeRes.status !== 200) {
      const body = await completeRes.json().catch(() => ({}));
      throw new Error(`完成上传失败：${body.message || completeRes.status}`);
    }
    const complete = await completeRes.json();
    $('#uploadProgress').hidden = true;
    $('#uploadResult').hidden = false;
    $('#uploadResultJson').textContent = JSON.stringify(complete, null, 2);
    $('#btnPublish').dataset.versionId = complete.version_id;
    if (notes) {
      // Apply notes via PATCH.
      await fetch(`/admin/versions/${complete.version_id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ notes }),
      });
    }
  } catch (err) {
    $('#uploadStage').textContent = `失败: ${err.message}`;
    console.error(err);
  } finally {
    startBtn.disabled = false;
  }
}

async function handlePublishClick(e) {
  const btn = e.target.closest('#btnPublish');
  if (!btn) return;
  const versionId = btn.dataset.versionId;
  const res = await fetch(`/admin/versions/${versionId}/publish`, {
    method: 'POST',
    credentials: 'same-origin',
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    $('#publishResult').textContent = `发布失败: ${body.message || res.status}`;
    return;
  }
  $('#publishResult').textContent = `已发布为 latest（前 latest: ${body.previous_latest || '无'}）`;
  $('#btnPublish').disabled = true;
}

function init() {
  $('#loginForm').addEventListener('submit', handleLoginSubmit);
  $('#nav').addEventListener('click', (e) => {
    const btn = e.target.closest('.nav-btn');
    if (!btn) return;
    if (btn.dataset.view === 'logout') {
      logout();
      return;
    }
    showView(btn.dataset.view);
    if (btn.dataset.view === 'versions') loadVersions();
    if (btn.dataset.view === 'audit') loadAudit();
  });
  $('#refreshVersions').addEventListener('click', loadVersions);
  $('#platformFilter').addEventListener('change', loadVersions);
  $('#versionsBody').addEventListener('click', handleVersionsClick);
  $('#refreshAudit').addEventListener('click', loadAudit);
  $('#uploadForm').addEventListener('submit', handleUploadSubmit);
  $('#uploadResult').addEventListener('click', handlePublishClick);

  // Auto-fill signature name when package is chosen.
  $('#packageFile').addEventListener('change', (e) => {
    if (!e.target.files[0]) return;
    const sig = $('#signatureFile');
    if (!sig.files[0]) sig.setAttribute('placeholder', signatureNameFor(e.target.files[0].name));
  });

  checkSession().then((ok) => {
    if (ok) enterConsole();
    else showView('login');
  });
}

init();
