const $ = selector => document.querySelector(selector);
let projectId = null;
let projectRows = [];

const today = new Date();
for (const input of document.querySelectorAll('.target-year')) input.value = today.getFullYear();
for (const input of document.querySelectorAll('.deployment-month')) {
  input.value = `${today.getFullYear()}${String(today.getMonth() + 1).padStart(2, '0')}`;
}

function toast(message, error = false) {
  const element = $('#toast');
  element.textContent = message;
  element.className = `toast show${error ? ' error' : ''}`;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => element.className = 'toast', 3500);
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

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"]/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'
  })[character]);
}

function setLoading(button, loading, label) {
  button.disabled = loading;
  button.innerHTML = loading ? '추출 중입니다…' : label;
}

const statusLabels = { DRAFT: '작성중', IMPORTED: '가져오기 완료', NEEDS_REVIEW: '검토 필요',
  VALIDATED: '검증 완료', APPROVED: '승인 완료', GENERATED: '생성 완료' };

async function loadProjects() {
  try {
    projectRows = await api('/api/projects');
    renderProjectList();
  } catch (error) {
    $('#project-list').innerHTML = `<tr><td colspan="8" class="project-empty">${escapeHtml(error.message)}</td></tr>`;
  }
}

function renderProjectList() {
  const keyword = $('#project-search').value.trim().toLowerCase();
  const status = $('#project-status-filter').value;
  const rows = projectRows.filter(row => (!status || row.status === status) && (!keyword ||
    row.systemName.toLowerCase().includes(keyword) || row.systemCode.toLowerCase().includes(keyword)));
  $('#project-list').innerHTML = rows.length ? rows.map(row => {
    const statusClass = row.status === 'APPROVED' ? 'approved' : row.status === 'GENERATED' ? 'generated' :
      row.status === 'NEEDS_REVIEW' ? 'review' : '';
    const updated = new Date(row.updatedAt).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' });
    return `<tr><td class="project-system"><strong>${escapeHtml(row.systemName)}</strong><small>${escapeHtml(row.systemCode)}</small></td>
      <td>v${row.revision}${row.active ? '' : '<span class="history-mark">이력</span>'}</td>
      <td>${row.targetYear} / ${escapeHtml(row.deploymentYearMonth)}</td>
      <td><span class="project-status ${statusClass}">${statusLabels[row.status] || row.status}</span></td>
      <td>${Number(row.itemCount).toLocaleString()}건</td><td>${row.unresolvedConflictCount}건</td>
      <td>${updated}</td><td><button type="button" class="project-open" data-project-id="${row.projectId}">열기</button></td></tr>`;
  }).join('') : '<tr><td colspan="8" class="project-empty">조건에 맞는 프로젝트가 없습니다.</td></tr>';
}

$('#refresh-projects').addEventListener('click', loadProjects);
$('#project-search').addEventListener('input', renderProjectList);
$('#project-status-filter').addEventListener('change', renderProjectList);
$('#project-list').addEventListener('click', async event => {
  const button = event.target.closest('[data-project-id]');
  if (!button) return;
  button.disabled = true;
  try { await openProject(Number(button.dataset.projectId)); }
  catch (error) { button.disabled = false; toast(error.message, true); }
});

async function openProject(id) {
  const overview = projectRows.find(row => row.projectId === id);
  const project = await api(`/api/projects/${id}`);
  projectId = id;
  $('#metadata').innerHTML = [
    ['입력 경로', '기존 프로젝트'], ['프로젝트', `#${id} · ${overview?.systemCode || ''} · v${overview?.revision || 1}`],
    ['시스템', overview?.systemName || project.systemName], ['상태', statusLabels[project.status] || project.status]
  ].map(([label, value]) => `<div><small>${label}</small><strong>${escapeHtml(value)}</strong></div>`).join('');
  renderProject(project);
  setArtifactLinks(id);
  $('#result-panel').classList.remove('locked');
  const approved = project.status === 'APPROVED' || project.status === 'GENERATED';
  for (const id of ['workbook-download', 'sql-download', 'exe-download', 'delete-exe-download'])
    $(`#${id}`).classList.toggle('disabled', !approved);
  $('#approve-button').disabled = approved;
  $('#approve-button').textContent = approved ? '승인 완료' : '검증하고 승인하기';
  $('#step-1').classList.remove('active');
  $('#step-2').classList.toggle('active', !approved);
  $('#step-3').classList.toggle('active', approved);
  $('#result-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function setArtifactLinks(id) {
  $('#workbook-download').href = `/api/projects/${id}/artifacts/workbook`;
  $('#sql-download').href = `/api/projects/${id}/artifacts/sql`;
  $('#exe-download').href = `/api/projects/${id}/artifacts/exe`;
  $('#delete-exe-download').href = `/api/projects/${id}/artifacts/delete-exe`;
}

$('#report-file').addEventListener('change', event => {
  $('#report-file-name').textContent = event.target.files[0]?.name || '';
});
$('#criteria-files').addEventListener('change', event => {
  $('#criteria-file-name').textContent = `${event.target.files.length}개 파일 선택됨`;
});

$('#report-form').addEventListener('submit', async event => {
  event.preventDefault();
  const button = event.currentTarget.querySelector('button');
  setLoading(button, true, '결과보고서에서 추출 <span>→</span>');
  try {
    const result = await api('/api/projects/from-result-report', { method: 'POST', body: new FormData(event.currentTarget) });
    completeImport(result, '결과보고서 추출');
  } catch (error) { toast(error.message, true); }
  finally { setLoading(button, false, '결과보고서에서 추출 <span>→</span>'); }
});

$('#criteria-form').addEventListener('submit', async event => {
  event.preventDefault();
  const button = event.currentTarget.querySelector('button');
  setLoading(button, true, '진단기준 직접 등록 <span>→</span>');
  try {
    const result = await api('/api/projects/from-criteria', { method: 'POST', body: new FormData(event.currentTarget) });
    completeImport(result, '진단기준 직접 등록');
  } catch (error) { toast(error.message, true); }
  finally { setLoading(button, false, '진단기준 직접 등록 <span>→</span>'); }
});

function completeImport(result, trackLabel) {
  projectId = result.project.projectId;
  const metadata = result.metadata;
  $('#metadata').innerHTML = [
    ['입력 경로', trackLabel], ['프로젝트', `#${projectId} · ${result.project.systemCode} · v${result.project.revision}`],
    ['시스템', metadata?.systemName || '직접 입력'], ['WISE DQ', metadata?.reportVersion ? `V${metadata.reportVersion}` : '9.0~9.2']
  ].map(([label, value]) => `<div><small>${label}</small><strong>${escapeHtml(value)}</strong></div>`).join('');
  renderImportSummary(result.importResult);
  renderConflicts([]);
  $('#result-panel').classList.remove('locked');
  $('#step-1').classList.remove('active');
  $('#step-2').classList.add('active');
  setArtifactLinks(projectId);
  api(`/api/projects/${projectId}`).then(project => renderConflicts(project.conflicts)).catch(() => {});
  toast(`${trackLabel}이 완료되었습니다.`);
  loadProjects();
  $('#result-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function renderImportSummary(result) {
  renderSummary(result.criteriaSummary, result.conflictCount, result.errors);
}

function renderProject(project) {
  const summary = { verificationRuleCount: 0, businessRuleCount: 0, tableExclusionCount: 0,
    columnExclusionCount: 0, columnMappingCount: 0 };
  for (const row of project.rows) {
    if (row.dataType === 'VERIFICATION_RULE') summary.verificationRuleCount++;
    if (row.dataType === 'BUSINESS_RULE') summary.businessRuleCount++;
    if (row.dataType === 'COLUMN_MAPPING') summary.columnMappingCount++;
    if (row.dataType === 'EXCLUSION' && row.values.exclusionType === 'COL') summary.columnExclusionCount++;
    if (row.dataType === 'EXCLUSION' && row.values.exclusionType !== 'COL') summary.tableExclusionCount++;
  }
  renderSummary(summary, project.conflicts.filter(conflict => !conflict.resolution).length, project.importErrors);
  renderConflicts(project.conflicts);
}

function renderSummary(summary, conflictCount, errors = []) {
  const cards = [
    ['추가 검증룰', summary.verificationRuleCount], ['업무규칙', summary.businessRuleCount],
    ['제외 테이블', summary.tableExclusionCount], ['제외 컬럼', summary.columnExclusionCount],
    ['컬럼 룰 매핑', summary.columnMappingCount], ['충돌', conflictCount]
  ];
  $('#summary').innerHTML = cards.map(([label, value]) =>
    `<div class="summary-card"><small>${label}</small><strong>${Number(value || 0).toLocaleString()}</strong></div>`).join('');
  $('#messages').innerHTML = errors.length
    ? errors.map(error => `<div class="message">${escapeHtml(error)}</div>`).join('')
    : '<div class="message ok">진단기준 추출 오류가 없습니다.</div>';
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
        <button type="button" data-resolution="EXCLUDE">항목 제외</button>
      </div>
    </article>`).join('');
}

$('#conflicts').addEventListener('click', async event => {
  const button = event.target.closest('button[data-resolution]');
  if (!button || !projectId) return;
  const card = button.closest('[data-conflict-id]');
  button.disabled = true;
  try {
    const project = await api(`/api/projects/${projectId}/conflicts/${card.dataset.conflictId}/resolution`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolution: button.dataset.resolution, reason: '화면에서 선택' })
    });
    renderProject(project);
    toast('충돌 해결 결과를 저장했습니다.');
  } catch (error) { button.disabled = false; toast(error.message, true); }
});

$('#approve-button').addEventListener('click', async () => {
  if (!projectId) return;
  const button = $('#approve-button');
  const approverName = $('#approver-name').value.trim();
  if (!approverName) return toast('승인 담당자 이름을 입력하세요.', true);
  button.disabled = true;
  try {
    const validation = await api(`/api/projects/${projectId}/validation`, { method: 'POST' });
    const errors = validation.issues.filter(issue => issue.severity === 'ERROR');
    if (validation.issues.length) $('#messages').innerHTML = validation.issues.map(issue =>
      `<div class="message${issue.severity === 'ERROR' ? '' : ' ok'}">${escapeHtml(issue.message)}</div>`).join('');
    if (errors.length) throw new Error(`승인 차단 오류가 ${errors.length}건 있습니다.`);
    const project = await api(`/api/projects/${projectId}/approval`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ approverName })
    });
    renderProject(project);
    for (const id of ['workbook-download', 'sql-download', 'exe-download', 'delete-exe-download']) $(`#${id}`).classList.remove('disabled');
    $('#step-2').classList.remove('active'); $('#step-3').classList.add('active');
    button.textContent = '승인 완료';
    toast('검증과 승인이 완료됐습니다.');
  } catch (error) { button.disabled = false; toast(error.message, true); }
});

for (const zone of document.querySelectorAll('.dropzone')) {
  zone.addEventListener('dragover', event => { event.preventDefault(); zone.classList.add('drag'); });
  zone.addEventListener('dragleave', () => zone.classList.remove('drag'));
  zone.addEventListener('drop', () => zone.classList.remove('drag'));
}

loadProjects();
