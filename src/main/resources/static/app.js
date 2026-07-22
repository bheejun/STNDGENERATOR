const $ = (selector) => document.querySelector(selector);
let projectId = null;

const today = new Date();
$('[name="targetYear"]').value = today.getFullYear();
$('[name="deploymentYearMonth"]').value = `${today.getFullYear()}${String(today.getMonth() + 1).padStart(2, '0')}`;

function toast(message, error = false) {
  const el = $('#toast');
  el.textContent = message;
  el.className = `toast show${error ? ' error' : ''}`;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.className = 'toast', 3500);
}

async function api(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) {
    let message = `요청 실패 (${response.status})`;
    try { message = (await response.json()).message || message; } catch (_) {}
    throw new Error(message);
  }
  return response.json();
}

function setLoading(button, loading, label) {
  button.disabled = loading;
  button.innerHTML = loading ? '분석 중입니다…' : label;
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"]/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'
  })[character]);
}

function bindFileLabel(input, output, multiple = false) {
  input.addEventListener('change', () => {
    output.textContent = multiple ? `${input.files.length}개 파일 선택됨` : (input.files[0]?.name || '');
  });
}

bindFileLabel($('#report-file'), $('#report-file-name'));
bindFileLabel($('#criteria-files'), $('#criteria-file-name'), true);

$('#report-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = event.currentTarget.querySelector('button');
  setLoading(button, true, '분석하고 프로젝트 만들기 <span>→</span>');
  try {
    const result = await api('/api/projects/from-result-report', { method: 'POST', body: new FormData(event.currentTarget) });
    projectId = result.project.projectId;
    const m = result.metadata;
    $('#metadata').innerHTML = [
      ['프로젝트', `#${projectId}`], ['기관', m.institutionName || '-'], ['시스템', m.systemName], ['WISE DQ', `V${m.reportVersion}`],
      ['DBMS', `${m.dbmsType} · ${m.dbmsName}`], ['스키마', m.schemaName], ['접속 주소', `${m.ipAddress || '-'}:${m.port || '-'}`], ['보고서 출력일', m.outputAt || '-']
    ].map(([label, value]) => `<div><small>${label}</small><strong>${escapeHtml(value)}</strong></div>`).join('');
    $('#criteria-panel').classList.remove('locked');
    $('#criteria-files').disabled = false;
    $('#criteria-form button').disabled = false;
    $('#step-1').classList.remove('active');
    $('#step-2').classList.add('active');
    renderSummary(result.importResult);
    toast(`프로젝트 #${projectId}가 생성되었습니다.`);
    $('#criteria-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (error) { toast(error.message, true); }
  finally { setLoading(button, false, '분석하고 프로젝트 만들기 <span>→</span>'); }
});

$('#criteria-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!projectId) return;
  const button = event.currentTarget.querySelector('button');
  setLoading(button, true, '기준 파일 분석하기 <span>→</span>');
  try {
    const result = await api(`/api/projects/${projectId}/imports`, { method: 'POST', body: new FormData(event.currentTarget) });
    renderSummary(result);
    const project = await api(`/api/projects/${projectId}`);
    renderConflicts(project.conflicts);
    $('#result-panel').classList.remove('locked');
    $('#step-2').classList.remove('active');
    $('#step-3').classList.add('active');
    $('#workbook-download').href = `/api/projects/${projectId}/artifacts/workbook`;
    $('#sql-download').href = `/api/projects/${projectId}/artifacts/sql`;
    toast(`${result.files.length}개 기준 파일 분석이 끝났습니다.`);
    $('#result-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (error) { toast(error.message, true); }
  finally { setLoading(button, false, '기준 파일 분석하기 <span>→</span>'); }
});

function renderSummary(result) {
  $('#summary').innerHTML = [
    ['정규화 데이터', result.normalizedRowCount], ['충돌', result.conflictCount],
    ['PT01 제외', result.excludedPt01Count], ['PT02 제외', result.excludedPt02Count]
  ].map(([label, value]) => `<div class="summary-card"><small>${label}</small><strong>${Number(value).toLocaleString()}</strong></div>`).join('');
  const errors = result.errors || [];
  $('#messages').innerHTML = errors.length
    ? errors.map(error => `<div class="message">${escapeHtml(error)}</div>`).join('')
    : '<div class="message ok">파싱 오류가 없습니다.</div>';
}

function renderProject(project) {
  renderSummary({
    normalizedRowCount: project.rows.length,
    conflictCount: project.conflicts.filter(conflict => !conflict.resolution).length,
    excludedPt01Count: project.excludedPt01Count,
    excludedPt02Count: project.excludedPt02Count,
    errors: project.importErrors
  });
  renderConflicts(project.conflicts);
}

function renderConflicts(items) {
  const unresolved = (items || []).filter(item => !item.resolution);
  if (!unresolved.length) {
    $('#conflicts').innerHTML = '<div class="conflict-empty">해결되지 않은 충돌이 없습니다.</div>';
    return;
  }
  $('#conflicts').innerHTML = `<h3 class="conflict-title">해결이 필요한 충돌 ${unresolved.length}건</h3>` + unresolved.map(item => `
    <article class="conflict-card" data-conflict-id="${item.id}">
      <div class="conflict-head"><strong>${escapeHtml(item.dataType)} · ${escapeHtml(item.logicalKey)}</strong><span>미해결</span></div>
      <div class="conflict-values">
        <div class="conflict-value"><small>왼쪽 · ${escapeHtml(item.leftSource)}</small><pre>${escapeHtml(JSON.stringify(item.leftValues, null, 2))}</pre></div>
        <div class="conflict-value"><small>오른쪽 · ${escapeHtml(item.rightSource)}</small><pre>${escapeHtml(JSON.stringify(item.rightValues, null, 2))}</pre></div>
      </div>
      <div class="conflict-buttons">
        <button type="button" data-resolution="USE_LEFT">왼쪽 값 사용</button>
        <button type="button" data-resolution="USE_RIGHT">오른쪽 값 사용</button>
        <button type="button" data-resolution="EXCLUDE">이 항목 제외</button>
      </div>
    </article>`).join('');
}

$('#conflicts').addEventListener('click', async (event) => {
  const button = event.target.closest('button[data-resolution]');
  if (!button || !projectId) return;
  const card = button.closest('[data-conflict-id]');
  button.disabled = true;
  try {
    const project = await api(`/api/projects/${projectId}/conflicts/${card.dataset.conflictId}/resolution`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolution: button.dataset.resolution, reason: '화면에서 선택' })
    });
    renderProject(project);
    toast('충돌 해결 결과를 저장했습니다.');
  } catch (error) {
    button.disabled = false;
    toast(error.message, true);
  }
});

$('#approve-button').addEventListener('click', async () => {
  if (!projectId) return;
  const button = $('#approve-button');
  const approverName = $('#approver-name').value.trim();
  if (!approverName) {
    toast('승인 담당자 이름을 입력하세요.', true);
    return;
  }
  button.disabled = true;
  try {
    const validation = await api(`/api/projects/${projectId}/validation`, { method: 'POST' });
    const errors = validation.issues.filter(issue => issue.severity === 'ERROR');
    if (validation.issues.length) {
      $('#messages').innerHTML = validation.issues.map(issue =>
        `<div class="message${issue.severity === 'ERROR' ? '' : ' ok'}">${escapeHtml(issue.message)}${issue.logicalKey ? ` · ${escapeHtml(issue.logicalKey)}` : ''}</div>`
      ).join('');
    }
    if (errors.length) throw new Error(`승인 차단 오류가 ${errors.length}건 있습니다.`);
    const project = await api(`/api/projects/${projectId}/approval`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ approverName })
    });
    renderProject(project);
    $('#workbook-download').classList.remove('disabled');
    $('#sql-download').classList.remove('disabled');
    button.textContent = '승인 완료';
    toast('검증과 승인이 완료되어 파일을 내려받을 수 있습니다.');
  } catch (error) {
    button.disabled = false;
    toast(error.message, true);
  }
});

for (const zone of document.querySelectorAll('.dropzone')) {
  zone.addEventListener('dragover', event => { event.preventDefault(); zone.classList.add('drag'); });
  zone.addEventListener('dragleave', () => zone.classList.remove('drag'));
  zone.addEventListener('drop', () => zone.classList.remove('drag'));
}
