const $ = selector => document.querySelector(selector);
let projectId = Number(sessionStorage.getItem('csr.currentProjectId')) || null;
let projectRows = [];
let managedCriteria = [];
let editingCriteria = null;
let criteriaPage = 1;
let criteriaErrorKeys = new Set();
const selectedCriteriaIds = new Set();
let systemRows = [];
let selectedSystemId = null;
const criteriaPageSize = 50;
const SYSTEM_FIELDS = ['systemCode','systemName','connectionLogicalName','dbmsPhysicalName','dbmsType',
  'defaultSchema','wdqNamespace','criteriaPrefix','active'];
const systemPrefixLabel = document.createElement('label');
systemPrefixLabel.innerHTML = '공통 prefix<input name="criteriaPrefix" maxlength="300" placeholder="[{year}표준시스템DB {systemName}]"><small class="field-help">{year}, {systemName}을 사용할 수 있으며 검증룰·업무규칙·제외 사유에 동일 적용됩니다.</small>';
const systemActiveField = document.querySelector('#system-edit-fields [name="active"]')?.closest('label');
if (systemActiveField) systemActiveField.before(systemPrefixLabel);
const DBMS_TYPES = [
  ['ORA', 'Oracle'], ['TIB', 'Tibero'], ['ALT', 'Altibase'], ['POS', 'PostgreSQL'],
  ['MRA', 'MariaDB'], ['MYS', 'MySQL'], ['MSQ', 'MS-SQL'], ['CBR', 'CUBRID'],
  ['UDB', 'DB2 UDB'], ['DB2', 'DB2'], ['SYA', 'Sybase ASE'], ['SYQ', 'Sybase IQ']
];
const CRITERIA_CATEGORY_LABELS = {
  WDQ_CRITERIA: '통합 진단기준', CRITERIA_VERIFICATION_RULE: '검증룰',
  CRITERIA_DOMAIN_MAPPING: '도메인 매핑',
  CRITERIA_BUSINESS_RULE: '업무규칙', CRITERIA_EXCLUSION_PATTERN: '제외기준 룰', CRITERIA_TABLE_EXCLUSION: '테이블 제외',
  CRITERIA_COLUMN_EXCLUSION: '컬럼 제외', CRITERIA_CODE_RULE: '코드규칙'
};
for (const input of document.querySelectorAll('input[name="dbmsType"]')) {
  const select = document.createElement('select');
  select.name = input.name;
  select.required = input.required;
  select.innerHTML = '<option value="">DBMS 선택</option>' +
    DBMS_TYPES.map(([code, name]) => `<option value="${code}">${name} (${code})</option>`).join('');
  input.replaceWith(select);
}
for (const button of document.querySelectorAll('dialog button[value="cancel"]')) {
  button.addEventListener('click', event => {
    event.preventDefault();
    button.closest('dialog')?.close('cancel');
  });
}
document.querySelectorAll('option[value="CREATE_SEPARATE"]').forEach(option => option.remove());
document.querySelectorAll('.main-nav [data-view="register"]')
  .forEach(button => button.remove());
document.querySelectorAll('.main-nav [data-view="dashboard"]').forEach(button => {
  if (button.textContent.trim() !== '전체 프로젝트 현황') button.remove();
});
document.querySelectorAll('select[name="duplicateHandling"] option[value="CREATE_VERSION"]')
  .forEach(option => option.textContent = '공통표준 시스템의 새 버전');

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

async function loadLatestReportReview(id) {
  const panel = $('#result-panel');
  let box = $('#report-review-result');
  if (!box) {
    box = document.createElement('section');
    box.id = 'report-review-result';
    box.className = 'report-review-result';
    panel.insertBefore(box, $('#metadata'));
  }
  const response = await fetch(`/api/projects/${id}/report-reviews/latest`);
  if (response.status === 404) {
    box.hidden = true;
    return;
  }
  if (!response.ok) {
    box.hidden = true;
    return;
  }
  const review = await response.json();
  const verdictLabel = { PASS: '적합', CONDITIONAL: '조건부 적합', FAIL: '부적합' }[review.verdict] || review.verdict;
  const metrics = review.metrics || {};
  const coverage = metrics.diagnosticRuleCoverageBasisPoints == null
    ? '-'
    : `${(Number(metrics.diagnosticRuleCoverageBasisPoints) / 100).toFixed(2)}%`;
  const metricCards = [
    ['진단대상', metrics.targetTableCount ?? 0, '건'],
    ['제외대상', metrics.excludedTableCount ?? 0, '건'],
    ['추가 검증룰', metrics.customRuleMappingCount ?? 0, '건'],
    ['업무규칙', metrics.businessRuleCount ?? 0, '건'],
    ['미완료 실행', metrics.incompleteExecutionCount ?? 0, '건'],
    ['미매핑 컬럼', metrics.missingDiagnosticRuleCount ?? 0, '건'],
    ['수행률', coverage, '']
  ].map(([label,value,unit]) => `<div><small>${label}</small><strong>${typeof value === 'number' ? Number(value).toLocaleString() : value}${unit}</strong></div>`).join('');
  const issues = (review.issues || []).map(issue =>
    `<li class="${issue.blocksAdoption ? 'blocking' : ''}"><strong>${escapeHtml(issue.code)}</strong> ${escapeHtml(issue.message)}
      <small>${escapeHtml(issue.sheetName || '')}${issue.rowNumber ? ` · ${issue.rowNumber}행` : ''}</small></li>`).join('');
  const decisionReasons = (review.decisionReasons || []).map(reason =>
    `<li><strong>${escapeHtml(reason.message)}</strong>
      ${reason.evidence ? `<span>${escapeHtml(reason.evidence)}</span>` : ''}
      <small>${escapeHtml(reason.code)}${reason.sheetName ? ` · ${escapeHtml(reason.sheetName)}` : ''}${reason.rowNumber ? ` · ${reason.rowNumber}행` : ''}</small></li>`).join('');
  const reasonBlock = review.verdict === 'PASS'
    ? ''
    : `<section class="decision-reasons ${review.verdict.toLowerCase()}"><h4>${verdictLabel} 판정 사유</h4>
        <ul>${decisionReasons || '<li><strong>판정 사유가 기록되지 않았습니다.</strong></li>'}</ul></section>`;
  box.hidden = false;
  box.innerHTML = `<div class="review-heading"><div><small>결과보고서 유효성 검토 · ${escapeHtml(review.engineVersion)}</small>
      <h3>공통표준 진단규칙 채택 검토</h3></div><span class="review-verdict ${review.verdict.toLowerCase()}">${verdictLabel}</span></div>
    <div class="review-metrics">${metricCards}</div>
    ${reasonBlock}
    ${issues ? `<ul class="review-issues">${issues}</ul>` : '<p class="review-ok">차단 오류와 확인 경고가 없습니다.</p>'}`;
}

async function api(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) {
    let message = `요청 실패 (${response.status})`;
    try { message = (await response.json()).message || message; } catch (_) {}
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  return response.headers.get('content-type')?.includes('application/json') ? response.json() : null;
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"]/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'
  })[character]);
}

function setCurrentProject(id) {
  const selected = Number(id) || null;
  projectId = selected;
  if (selected) sessionStorage.setItem('csr.currentProjectId', String(selected));
  else sessionStorage.removeItem('csr.currentProjectId');
  for (const selector of ['#existing-project', '#report-project', '#criteria-upload-project',
    '#manage-project', '#artifact-project-select', '#sql-comparison-project']) {
    const element = $(selector);
    if (element && [...element.options].some(option => Number(option.value) === selected))
      element.value = String(selected);
  }
}

const statusLabels = { DRAFT: '작성중', IMPORTED: '가져오기 완료', NEEDS_REVIEW: '검토 필요',
  VALIDATED: '검증 완료', APPROVED: '승인 완료', GENERATED: '생성 완료' };

const VIEW_ROUTES = { systems: '#/systems', dashboard: '#/projects', artifacts: '#/artifacts',
  ids: '#/numbering', register: '#/register', manage: '#/manage', results: '#/results',
  reportUpload: '#/upload/result-report', criteriaUpload: '#/upload/criteria',
  sqlCompare: '#/sql-comparison' };

function updateRoute(hash) {
  if (window.location.hash === hash) return;
  history.pushState(null, '', hash);
}

function showView(view, route = true) {
  document.querySelectorAll('[data-view-panel]').forEach(panel => panel.classList.toggle('active', panel.dataset.viewPanel === view));
  document.querySelectorAll('.main-nav [data-view]').forEach(button => button.classList.toggle('active', button.dataset.view === view));
  if (route) updateRoute(VIEW_ROUTES[view] || `#/${view}`);
  window.scrollTo({ top: 0, behavior: 'smooth' });
  if (view === 'systems') loadSystems();
  if (view === 'ids') loadSystems().then(loadIdPolicies);
  if (view === 'artifacts') loadArtifactHistory();
  if (view === 'sqlCompare') loadSqlComparison();
}

document.querySelector('.main-nav').addEventListener('click', event => {
  const button = event.target.closest('[data-view]');
  if (!button) return;
  if (button.dataset.view === 'results') {
    const id = projectId || Number($('#existing-project').value) || projectRows.find(row => row.active)?.projectId;
    if (id) return openProject(id);
  }
  showView(button.dataset.view);
  if (button.dataset.view === 'manage' && projectId) loadManagedCriteria();
});

async function loadProjects() {
  try {
    projectRows = await api('/api/projects');
    renderProjectList();
    renderProjectSelectors();
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
      <td>${updated}</td><td class="row-actions"><button type="button" class="project-open" data-project-id="${row.projectId}">결과 보기</button><button type="button" class="project-open" data-edit-project="${row.projectId}">수정</button><button type="button" class="project-open danger-button" data-delete-project="${row.projectId}">삭제</button></td></tr>`;
  }).join('') : '<tr><td colspan="8" class="project-empty">조건에 맞는 프로젝트가 없습니다.</td></tr>';
}

$('#refresh-projects').addEventListener('click', loadProjects);
$('#project-search').addEventListener('input', renderProjectList);
$('#project-status-filter').addEventListener('change', renderProjectList);
$('#project-list').addEventListener('click', async event => {
  const deleteButton = event.target.closest('[data-delete-project]');
  if (deleteButton) return deleteProject(Number(deleteButton.dataset.deleteProject), deleteButton);
  const editButton = event.target.closest('[data-edit-project]');
  if (editButton) return openProjectEditor(Number(editButton.dataset.editProject));
  const button = event.target.closest('[data-project-id]');
  if (!button) return;
  button.disabled = true;
  try { await openProject(Number(button.dataset.projectId)); }
  catch (error) { button.disabled = false; toast(error.message, true); }
});

async function openProjectEditor(id) {
  try {
    const overview = projectRows.find(row => row.projectId === id);
    const project = await api(`/api/projects/${id}`);
    const form = $('#project-edit-fields'); form.dataset.projectId = id;
    const values = { systemCode: overview.systemCode, systemName: overview.systemName, dbmsType: project.dbmsType,
      dbmsPhysicalName: project.dbmsPhysicalName, defaultSchema: project.defaultSchema,
      targetYear: overview.targetYear, deploymentYearMonth: overview.deploymentYearMonth };
    for (const [key, value] of Object.entries(values)) form.querySelector(`[name="${key}"]`).value = value || '';
    $('#project-edit-dialog').showModal();
  } catch (error) { toast(error.message, true); }
}

$('#save-project').addEventListener('click', async () => {
  const fields = $('#project-edit-fields'); const id = Number(fields.dataset.projectId);
  const values = Object.fromEntries([...fields.querySelectorAll('[name]')].map(input => [input.name, input.value]));
  values.targetYear = Number(values.targetYear);
  const button = $('#save-project'); button.disabled = true;
  try {
    await api(`/api/projects/${id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(values) });
    $('#project-edit-dialog').close(); await loadProjects(); toast('프로젝트 기본정보를 수정했습니다.');
  } catch (error) { toast(error.message, true); }
  finally { button.disabled = false; }
});

async function deleteProject(id, button) {
  const overview = projectRows.find(row => row.projectId === id);
  if (!overview) return toast('삭제할 프로젝트를 찾을 수 없습니다.', true);
  const label = `${overview.systemName} / ${overview.targetYear} / v${overview.revision}`;
  if (!window.confirm(`${label} 프로젝트를 삭제하시겠습니까?\n\n업로드 파일, 추출 기준, 검토 결과와 생성 이력이 함께 삭제됩니다. 이 작업은 되돌릴 수 없습니다.`)) return;
  if (button) button.disabled = true;
  try {
    await api(`/api/projects/${id}`, { method: 'DELETE' });
    if (projectId === id) {
      setCurrentProject(null);
      showView('dashboard');
    }
    await loadProjects();
    await loadSystems();
    if (selectedSystemId && systemRows.some(system => system.id === selectedSystemId)) {
      await openSystemWorkspace(selectedSystemId);
    }
    toast(`${label} 프로젝트를 삭제했습니다.`);
  } catch (error) {
    toast(error.message, true);
  } finally {
    if (button?.isConnected) button.disabled = false;
  }
}

async function openProject(id, route = true) {
  const overview = projectRows.find(row => row.projectId === id);
  const project = await api(`/api/projects/${id}`);
  setCurrentProject(id);
  showView('results', false);
  if (route) updateRoute(`#/results/${id}`);
  $('#existing-project').value = String(id);
  $('#metadata').innerHTML = [
    ['입력 경로', '기존 프로젝트'], ['프로젝트', `#${id} · ${overview?.systemCode || ''} · v${overview?.revision || 1}`],
    ['시스템', overview?.systemName || project.systemName], ['상태', statusLabels[project.status] || project.status]
  ].map(([label, value]) => `<div><small>${label}</small><strong>${escapeHtml(value)}</strong></div>`).join('');
  renderProject(project);
  await renderResultValidation(id);
  await loadProjectFiles(id);
  await loadLatestReportReview(id);
  setArtifactLinks(id);
  $('#result-panel').classList.remove('locked');
  for (const id of ['workbook-download', 'ddl-download', 'combined-sql-download', 'sql-download', 'exe-download', 'delete-exe-download'])
    $(`#${id}`).classList.remove('disabled');
  $('#step-1').classList.add('active');
  $('#step-2').classList.add('active');
  $('#step-3').classList.add('active');
  (overview?.sourceFileCount ? $('#result-panel') : document.querySelector('.existing-import-panel'))
    .scrollIntoView({ behavior: 'smooth', block: 'start' });
}

$('#existing-project').addEventListener('change', event => {
  const id = Number(event.target.value);
  if (id && id !== projectId) openProject(id).catch(error => toast(error.message, true));
});
document.querySelector('.result-context-bar').addEventListener('click', event => {
  const button = event.target.closest('[data-go-upload]');
  if (button) showView(button.dataset.goUpload);
});
$('#report-project').addEventListener('change', event => setCurrentProject(event.target.value));
$('#criteria-upload-project').addEventListener('change', event => {
  setCurrentProject(event.target.value);
  criteriaUploadRows = [];
  renderCriteriaUploadStatus();
});

async function loadProjectFiles(id) {
  const box=$('#project-source-files');
  if(!box)return;
  const files=await api(`/api/projects/${id}/files`);
  box.innerHTML=files.length?`<h3>등록된 원본 파일</h3>${files.map(file=>`
    <div class="source-file-row"><div><strong>${escapeHtml(file.originalName)}</strong>
    <small><span class="source-track ${file.inputTrack === 'RESULT_REPORT' ? 'report' : 'criteria'}">${file.inputTrack === 'RESULT_REPORT' ? '결과보고서' : file.inputTrack === 'CRITERIA_FILES' ? '진단기준' : '유형 확인 전'}</span>
    ${escapeHtml(file.inputTrack === 'RESULT_REPORT' ? 'WISE DQ 결과보고서' : CRITERIA_CATEGORY_LABELS[file.workbookType] || file.workbookType || '')} · ${escapeHtml(file.parseStatus)} · ${Number(file.byteSize).toLocaleString()} B · ${new Date(file.createdAt).toLocaleString('ko-KR')}</small></div>
    <a class="secondary" href="/api/projects/${id}/files/${file.id}">다운로드</a></div>`).join('')}`
    :'<div class="project-empty">등록된 원본 파일이 없습니다.</div>';
}

function renderProjectSelectors() {
  const active = projectRows.filter(row => row.active);
  const options = active.map(row => `<option value="${row.projectId}">${escapeHtml(row.systemName)} · ${escapeHtml(row.systemCode)} · v${row.revision} · ${statusLabels[row.status] || row.status}</option>`).join('');
  $('#existing-project').innerHTML = options || '<option value="">등록된 프로젝트 없음</option>';
  $('#report-project').innerHTML = '<option value="">프로젝트 선택</option>' + options;
  $('#criteria-upload-project').innerHTML = '<option value="">프로젝트 선택</option>' + options;
  const previous = $('#manage-project').value;
  $('#manage-project').innerHTML = '<option value="">프로젝트 선택</option>' + options;
  if (active.some(row => String(row.projectId) === previous)) $('#manage-project').value = previous;
  const artifactPrevious = $('#artifact-project-select')?.value;
  if ($('#artifact-project-select')) {
    $('#artifact-project-select').innerHTML = '<option value="">프로젝트 선택</option>' + options;
    if (active.some(row => String(row.projectId) === artifactPrevious)) $('#artifact-project-select').value = artifactPrevious;
  }
  const comparisonPrevious = $('#sql-comparison-project')?.value;
  if ($('#sql-comparison-project')) {
    $('#sql-comparison-project').innerHTML = '<option value="">프로젝트 선택</option>' + options;
    if (active.some(row => String(row.projectId) === comparisonPrevious))
      $('#sql-comparison-project').value = comparisonPrevious;
  }
  if (projectId && active.some(row => row.projectId === projectId)) setCurrentProject(projectId);
  else if (projectId) setCurrentProject(null);
}

$('#project-register-form').addEventListener('submit', async event => {
  event.preventDefault();
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    const values = Object.fromEntries(new FormData(event.currentTarget));
    values.targetYear = Number(values.targetYear);
    const created = await api('/api/projects', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(values) });
    event.currentTarget.reset();
    for (const input of event.currentTarget.querySelectorAll('.target-year')) input.value = today.getFullYear();
    for (const input of event.currentTarget.querySelectorAll('.deployment-month')) input.value = `${today.getFullYear()}${String(today.getMonth() + 1).padStart(2, '0')}`;
    await loadProjects(); showView('dashboard');
    toast(`프로젝트 #${created.projectId}가 등록되었습니다.`);
  } catch (error) { toast(error.message, true); }
  finally { button.disabled = false; }
});

async function importExistingFiles(event, projectSelector, fileSelector, track, successMessage) {
  event.preventDefault();
  const id = Number($(projectSelector).value);
  if (!id) return toast('가져올 프로젝트를 선택하세요.', true);
  setCurrentProject(id);
  const files = [...$(fileSelector).files];
  if (!files.length) return toast('등록할 엑셀 파일을 선택하세요.', true);
  const button = event.currentTarget.querySelector('button[type="submit"]'); button.disabled = true;
  try {
    const form = new FormData();
    for (const file of files) form.append('files', file);
    await api(`/api/projects/${id}/imports/${track}`, { method: 'POST', body: form });
    await loadProjects(); await openProject(id);
    toast(successMessage);
  } catch (error) { toast(error.message, true); }
  finally { button.disabled = false; }
}

$('#existing-report-form').addEventListener('submit', event =>
  importExistingFiles(event, '#report-project', '#existing-report-file', 'result-report', '결과보고서 분석과 등록을 완료했습니다.'));

let criteriaUploadRows = [];
function renderCriteriaUploadStatus() {
  const box = $('#criteria-upload-status');
  if (!criteriaUploadRows.length) { box.hidden = true; box.innerHTML = ''; return; }
  const success = criteriaUploadRows.filter(row => row.state === 'success').length;
  const failed = criteriaUploadRows.filter(row => row.state === 'failed').length;
  box.hidden = false;
  box.innerHTML = `<div class="criteria-upload-summary"><strong>업로드 현황 ${criteriaUploadRows.length}개</strong>
    <span>완료 ${success} · 실패 ${failed} · 처리 중 ${criteriaUploadRows.length - success - failed}</span></div>` +
    criteriaUploadRows.map(row => `<div class="criteria-upload-status-row ${row.state}">
      <div><strong>${escapeHtml(row.name)}</strong><small>${escapeHtml(row.detail || '')}</small></div>
      <span>${escapeHtml(row.category || '형식 확인 중')}</span><span class="state">${escapeHtml(row.label)}</span>
    </div>`).join('');
}
async function uploadCriteriaFile(projectIdValue, file, row, category = '') {
  row.state = 'uploading'; row.label = '업로드 중'; row.detail = `${(file.size / 1024 / 1024).toFixed(1)} MB`;
  renderCriteriaUploadStatus();
  try {
    const form = new FormData(); form.append('files', file);
    const query = category ? `?category=${encodeURIComponent(category)}` : '';
    const result = await api(`/api/projects/${projectIdValue}/imports/criteria${query}`,
      { method: 'POST', body: form });
    const imported = result.files?.[0] || {};
    row.state = 'success'; row.label = '등록 완료';
    row.category = CRITERIA_CATEGORY_LABELS[imported.workbookType] || imported.workbookType || category || '진단기준';
    row.detail = `${Number(imported.candidateCount || 0).toLocaleString()}건 인식 · 프로젝트 총 ${Number(result.normalizedRowCount || 0).toLocaleString()}건`;
  } catch (error) {
    row.state = 'failed'; row.label = '등록 실패'; row.detail = error.message;
  }
  renderCriteriaUploadStatus();
}

$('#criteria-batch-select').addEventListener('click', () => $('#criteria-batch-files').click());
$('#criteria-batch-files').addEventListener('change', async event => {
  const files = [...event.target.files];
  if (!files.length) return;
  const id = Number($('#criteria-upload-project').value);
  if (!id) { event.target.value = ''; return toast('대상 프로젝트를 선택하세요.', true); }
  setCurrentProject(id);
  const button = $('#criteria-batch-select');
  button.disabled = true; button.textContent = `${files.length}개 등록 중`;
  criteriaUploadRows = files.map(file => ({
    name: file.name, state: 'pending', label: '대기', category: '',
    detail: `${(file.size / 1024 / 1024).toFixed(1)} MB`
  }));
  renderCriteriaUploadStatus();
  for (let index = 0; index < files.length; index++)
    await uploadCriteriaFile(id, files[index], criteriaUploadRows[index]);
  event.target.value = '';
  button.disabled = false; button.textContent = '여러 파일 선택';
  await loadProjects();
});

$('#criteria-upload-grid').addEventListener('click', event => {
  const button = event.target.closest('.criteria-upload-card button');
  if (button && !button.disabled) button.closest('.criteria-upload-card').querySelector('input[type="file"]').click();
});
$('#criteria-upload-grid').addEventListener('change', async event => {
  const input = event.target.closest('.criteria-upload-card input[type="file"]');
  if (!input || !input.files.length) return;
  const id = Number($('#criteria-upload-project').value);
  if (!id) { input.value = ''; return toast('가져올 프로젝트를 선택하세요.', true); }
  setCurrentProject(id);
  const card = input.closest('.criteria-upload-card');
  const button = card.querySelector('button');
  const originalLabel = button.textContent;
  button.disabled = true;
  button.textContent = `${input.files.length}개 등록 중…`;
  const files = [...input.files];
  const rows = files.map(file => ({
    name: file.name, state: 'pending', label: '대기',
    category: card.querySelector('strong').textContent, detail: ''
  }));
  criteriaUploadRows.push(...rows);
  renderCriteriaUploadStatus();
  try {
    for (let index = 0; index < files.length; index++)
      await uploadCriteriaFile(id, files[index], rows[index], card.dataset.category);
    input.value = '';
    await loadProjects();
  } catch (error) { toast(error.message, true); }
  finally { button.disabled = false; button.textContent = originalLabel; }
});

document.querySelector('.project-detail-tools').addEventListener('click', async event => {
  const button = event.target.closest('[data-reextract-track]');
  if (!button) return;
  const id = Number($('#existing-project').value);
  if (!id) return toast('재추출할 프로젝트를 선택하세요.', true);
  const track = button.dataset.reextractTrack;
  const label = track === 'result-report' ? '결과보고서' : track === 'criteria' ? '진단기준' : '전체 원본';
  if (!confirm(`${label}을 다시 파싱하시겠습니까?\n직접 수정한 진단기준은 유지됩니다.`)) return;
  button.disabled = true;
  try {
    const endpoint = track === 'all' ? `/api/projects/${id}/reextract` : `/api/projects/${id}/reextract/${track}`;
    const result = await api(endpoint, { method: 'POST' });
    await loadProjects();
    await openProject(id);
    if (Number($('#manage-project').value) === id) {
      ddlValidationProject = 0;
      await loadManagedCriteria();
    }
    toast(`${label} 재추출 완료: 병합 진단기준 ${Number(result.normalizedRowCount).toLocaleString()}건`);
  } catch (error) { toast(error.message, true); }
  finally { button.disabled = false; }
});

$('#manage-project').addEventListener('change', event => {
  setCurrentProject(event.target.value);
  loadManagedCriteria();
});
$('#criteria-type').addEventListener('change', () => { criteriaPage = 1; renderManagedCriteria(); });
$('#criteria-validation-filter').addEventListener('change', () => { criteriaPage = 1; renderManagedCriteria(); });
$('#criteria-search').addEventListener('input', () => { criteriaPage = 1; renderManagedCriteria(); });
$('#criteria-prev').addEventListener('click', () => { if (criteriaPage > 1) { criteriaPage--; renderManagedCriteria(); } });
$('#criteria-next').addEventListener('click', () => { criteriaPage++; renderManagedCriteria(); });

async function loadManagedCriteria() {
  const id = Number($('#manage-project').value);
  if (!id) { managedCriteria = []; return renderManagedCriteria(); }
  $('#criteria-list').innerHTML = '<tr><td colspan="4" class="project-empty">진단기준을 불러오는 중입니다.</td></tr>';
  try { managedCriteria = await api(`/api/projects/${id}/criteria`); selectedCriteriaIds.clear(); criteriaPage = 1; renderManagedCriteria(); }
  catch (error) { toast(error.message, true); }
}

$('#criteria-revalidate-button').addEventListener('click', async event => {
  const project = Number($('#manage-project').value);
  if (!project) return toast('재검증할 프로젝트를 먼저 선택하세요.', true);
  const button = event.currentTarget;
  button.disabled = true;
  button.textContent = '검증 중...';
  try {
    ddlValidationProject = 0;
    await loadManagedCriteria();
    toast('최신 저장값으로 WDQ 재검증을 완료했습니다.');
  } catch (error) { toast(error.message, true); }
  finally {
    button.disabled = false;
    button.textContent = 'WDQ 재검증';
  }
});

function renderManagedCriteria() {
  renderCodeDataWarning();
  renderDdlValidation();
  const type = $('#criteria-type').value;
  const validation = $('#criteria-validation-filter').value;
  const keyword = $('#criteria-search').value.trim().toLowerCase();
  const rows = managedCriteria.filter(item => (!type || item.dataType === type)
    && (!validation || (validation === 'ERROR') === criteriaErrorKeys.has(item.logicalKey))
    && (!keyword || item.logicalKey.toLowerCase().includes(keyword)
      || JSON.stringify(item.values).toLowerCase().includes(keyword)));
  const pageCount = Math.max(1, Math.ceil(rows.length / criteriaPageSize));
  criteriaPage = Math.min(criteriaPage, pageCount);
  const pageRows = rows.slice((criteriaPage - 1) * criteriaPageSize, criteriaPage * criteriaPageSize);
  $('#criteria-count').textContent = `${rows.length.toLocaleString()}건`;
  $('#criteria-page').textContent = `${criteriaPage} / ${pageCount}`;
  $('#criteria-prev').disabled = criteriaPage <= 1;
  $('#criteria-next').disabled = criteriaPage >= pageCount;
  $('#criteria-list').innerHTML = pageRows.length ? pageRows.map(item => {
    const preview = Object.entries(item.values).filter(([key, value]) => key !== 'wdqId' && value).slice(0, 3)
      .map(([key, value]) => `${key}: ${value}`).join(' · ');
    const origin = item.dataType === 'VERIFICATION_RULE'
      && (item.values.ruleOrigin?.startsWith('ADDITIONAL') || item.values.sourceRuleName)
      ? '<span class="criteria-origin">추가 검증룰</span>' : '';
    const missingType = item.dataType === 'VERIFICATION_RULE'
      && item.values.ruleOrigin === 'ADDITIONAL_EXTRACTED'
      && !['YN', 'RNG', 'FRM', 'DTM', 'NO', 'NN'].includes(String(item.values.ruleType || '').toUpperCase())
      ? '<span class="criteria-required">진단유형 선택 필요</span>' : '';
    return `<tr><td class="check-cell"><input type="checkbox" data-select-criteria="${item.id}" ${selectedCriteriaIds.has(item.id) ? 'checked' : ''}></td><td><span class="criteria-type">${escapeHtml(item.dataType)}</span>${origin}${missingType}</td><td class="criteria-key">${escapeHtml(item.logicalKey)}</td><td class="criteria-preview">${escapeHtml(preview)}</td><td class="row-actions"><button type="button" class="project-open" data-edit-item="${item.id}">수정</button><button type="button" class="project-open danger-button" data-delete-item="${item.id}">삭제</button></td></tr>`;
  }).join('') : '<tr><td colspan="5" class="project-empty">표시할 진단기준이 없습니다.</td></tr>';
  const allSelected = rows.length > 0 && rows.every(item => selectedCriteriaIds.has(item.id));
  $('#criteria-select-filtered').checked = allSelected;
  $('#criteria-select-filtered').indeterminate = !allSelected && rows.some(item => selectedCriteriaIds.has(item.id));
  $('#criteria-selected-count').textContent = `${selectedCriteriaIds.size.toLocaleString()}건 선택`;
  $('#criteria-bulk-button').disabled = selectedCriteriaIds.size === 0;
}

let ddlValidationProject = 0;
let activeValidationProject = 0;
let activeValidationIssues = [];
const validationCodeLabels = {
  MISSING_VERIFICATION_TYPE: '진단유형 미설정',
  UNKNOWN_QUALITY_INDICATOR: '품질지표 카탈로그 불일치',
  WDQ_COLUMN_TOO_LONG: 'WDQ 컬럼 길이 초과',
  BUSINESS_TARGET_TABLE_NOT_DIAGNOSTIC: '업무규칙 테이블 미등록',
  BUSINESS_TARGET_COLUMN_NOT_DIAGNOSTIC: '업무규칙 컬럼 미등록',
  BUSINESS_TARGET_COLUMN_METADATA_MISSING: '업무규칙 컬럼 메타정보 없음',
  BUSINESS_TARGET_SCHEMA_EXCLUDED: '업무규칙 스키마 제외',
  BUSINESS_TARGET_TABLE_EXCLUDED: '업무규칙 테이블 제외 충돌',
  BUSINESS_TARGET_TABLE_PATTERN_EXCLUDED: '업무규칙 테이블명 제외 충돌',
  BUSINESS_TARGET_COLUMN_EXCLUDED: '업무규칙 컬럼 제외 충돌',
  BUSINESS_TARGET_COLUMN_MISSING: '업무규칙 대상 컬럼 미입력',
};

function relevantValidationIssues(report) {
  return (report.issues || []).filter(issue =>
    issue.code === 'WDQ_COLUMN_TOO_LONG'
    || issue.code.startsWith('BUSINESS_TARGET_')
    || issue.severity === 'ERROR');
}

function validationSummaryHtml(issues) {
  const errors = issues.filter(issue => issue.severity === 'ERROR').length;
  const warnings = issues.length - errors;
  if (!issues.length) return `<div class="validation-summary-card ok">
    <div class="validation-summary-icon">✓</div><div class="validation-summary-copy">
      <strong>생성 기준 검증 완료</strong><span>WDQ 입력 오류와 업무규칙 진단대상 불일치가 없습니다.</span>
    </div></div>`;
  return `<div class="validation-summary-card">
    <div class="validation-summary-icon">!</div><div class="validation-summary-copy">
      <strong>검증 확인 필요 ${issues.length.toLocaleString()}건</strong>
      <span>항목을 유형별로 모아 상세 팝업에서 확인할 수 있습니다.</span>
    </div><div class="validation-summary-counts">
      ${errors ? `<span class="validation-count error">오류 ${errors}</span>` : ''}
      ${warnings ? `<span class="validation-count">확인 ${warnings}</span>` : ''}
    </div><button type="button" class="secondary" data-open-validation>상세보기</button></div>`;
}

async function fetchProjectValidation(project, force = false) {
  if (!force && activeValidationProject === project) return activeValidationIssues;
  const report = await api(`/api/projects/${project}/validation`, { method: 'POST' });
  activeValidationProject = project;
  activeValidationIssues = relevantValidationIssues(report);
  return activeValidationIssues;
}

async function renderResultValidation(project) {
  const box = $('#result-validation-summary');
  if (!box) return;
  try { box.innerHTML = validationSummaryHtml(await fetchProjectValidation(project, true)); }
  catch (error) { box.innerHTML = `<div class="message">${escapeHtml(error.message)}</div>`; }
}

async function renderDdlValidation() {
  const project = Number($('#manage-project').value);
  const box = $('#ddl-validation-result');
  if (!project || project === ddlValidationProject) return;
  ddlValidationProject = project;
  try {
    const issues = await fetchProjectValidation(project, true);
    criteriaErrorKeys = new Set(issues.map(issue => issue.logicalKey).filter(Boolean));
    box.hidden = false;
    box.innerHTML = validationSummaryHtml(issues);
    renderManagedCriteria();
  } catch (error) {
    ddlValidationProject = 0;
    box.hidden = false;
    box.innerHTML = `<div class="message">${escapeHtml(error.message)}</div>`;
  }
}

function renderValidationDialog() {
  const code = $('#validation-code-filter').value;
  const keyword = $('#validation-search').value.trim().toLowerCase();
  const issues = activeValidationIssues.filter(issue =>
    (!code || issue.code === code)
    && (!keyword || `${issue.message} ${issue.logicalKey || ''} ${issue.code}`.toLowerCase().includes(keyword)));
  const groups = issues.reduce((result, issue) => {
    if (!result.has(issue.code)) result.set(issue.code, []);
    result.get(issue.code).push(issue);
    return result;
  }, new Map());
  $('#validation-issue-list').innerHTML = issues.length ? [...groups.entries()].map(([issueCode, rows]) =>
    `<section class="validation-issue-group"><div class="validation-issue-group-title">
      <span>${escapeHtml(validationCodeLabels[issueCode] || issueCode)}</span><span>${rows.length}건</span></div>
      ${rows.map(issue => `<div class="validation-issue-row">
        <span class="validation-severity ${issue.severity === 'ERROR' ? 'error' : ''}">${issue.severity === 'ERROR' ? '오류' : '확인 필요'}</span>
        <div><strong>${escapeHtml(issue.message)}</strong>${issue.logicalKey ? `<small>${escapeHtml(issue.logicalKey)}</small>` : ''}</div>
        ${issue.logicalKey ? `<button type="button" class="secondary" data-edit-validation-key="${escapeHtml(issue.logicalKey)}">수정</button>` : ''}
      </div>`).join('')}</section>`).join('')
    : '<div class="validation-empty">조건에 맞는 검증 항목이 없습니다.</div>';
}

function openValidationDialog() {
  const errors = activeValidationIssues.filter(issue => issue.severity === 'ERROR').length;
  const codes = [...new Set(activeValidationIssues.map(issue => issue.code))].sort();
  $('#validation-dialog-summary').innerHTML = `<span>전체 ${activeValidationIssues.length}건</span>
    <span class="error">오류 ${errors}건</span><span class="warning">확인 필요 ${activeValidationIssues.length - errors}건</span>`;
  $('#validation-code-filter').innerHTML = '<option value="">전체 유형</option>' + codes.map(code =>
    `<option value="${escapeHtml(code)}">${escapeHtml(validationCodeLabels[code] || code)}</option>`).join('');
  $('#validation-search').value = '';
  renderValidationDialog();
  $('#validation-dialog').showModal();
}

document.addEventListener('click', event => {
  if (event.target.closest('[data-open-validation]')) openValidationDialog();
});
$('#validation-code-filter').addEventListener('change', renderValidationDialog);
$('#validation-search').addEventListener('input', renderValidationDialog);
$('#validation-issue-list').addEventListener('click', async event => {
  const button = event.target.closest('[data-edit-validation-key]');
  if (!button) return;
  $('#validation-dialog').close();
  showView('manage');
  $('#manage-project').value = String(activeValidationProject);
  setCurrentProject(activeValidationProject);
  await loadManagedCriteria();
  const item = managedCriteria.find(row => row.logicalKey === button.dataset.editValidationKey);
  if (!item) return toast('수정할 진단기준을 현재 목록에서 찾을 수 없습니다.', true);
  $('#criteria-type').value = item.dataType;
  $('#criteria-search').value = item.logicalKey;
  criteriaPage = 1;
  renderManagedCriteria();
  document.querySelector(`[data-edit-item="${item.id}"]`)?.click();
});

function renderCodeDataWarning() {
  const box = $('#code-data-warning');
  const valueRules = new Set(managedCriteria.filter(item => item.dataType === 'CODE_VALUE')
    .map(item => String(item.values.ruleName || '').trim()).filter(Boolean));
  const sqlRules = new Set(managedCriteria.filter(item => item.dataType === 'CODE_RULE'
      && String(item.values.lookupSql || '').trim())
    .map(item => String(item.values.ruleName || '').trim()).filter(Boolean));
  const missing = [...new Set(managedCriteria.filter(item => item.dataType === 'COLUMN_MAPPING'
      && String(item.values.ruleType || '').toUpperCase() === 'CODE')
    .map(item => String(item.values.ruleName || item.values.codeRuleId || '').trim())
    .filter(name => name && !valueRules.has(name) && !sqlRules.has(name)))];
  box.hidden = missing.length === 0;
  box.innerHTML = missing.length
    ? `<div class="message warning"><strong>코드 데이터 미등록</strong><span>코드생성 SQL과 직접 등록 코드값이 모두 없는 규칙: ${missing.map(escapeHtml).join(', ')}</span><small>SQL 방식을 사용하거나 신규 추가 → 코드 데이터에서 코드값과 코드명을 등록하세요.</small></div>`
    : '';
}

$('#criteria-list').addEventListener('click', event => {
  const selection = event.target.closest('[data-select-criteria]');
  if (selection) {
    const id = Number(selection.dataset.selectCriteria);
    if (selection.checked) selectedCriteriaIds.add(id); else selectedCriteriaIds.delete(id);
    return renderManagedCriteria();
  }
  const deleteButton = event.target.closest('[data-delete-item]');
  if (deleteButton) return deleteCriteria(Number(deleteButton.dataset.deleteItem));
  const button = event.target.closest('[data-edit-item]');
  if (!button) return;
  editingCriteria = managedCriteria.find(item => item.id === Number(button.dataset.editItem));
  $('#edit-criteria-key').textContent = `${editingCriteria.dataType} · ${editingCriteria.logicalKey}`;
  const editableValues = { ...editingCriteria.values };
  if (editingCriteria.dataType === 'VERIFICATION_RULE'
      && editingCriteria.values.ruleOrigin === 'ADDITIONAL_EXTRACTED'
      && !Object.hasOwn(editableValues, 'ruleType')) editableValues.ruleType = '';
  $('#edit-fields').innerHTML = Object.entries(editableValues).map(([key, value]) => {
    if (key === 'ruleType' && editingCriteria.dataType === 'VERIFICATION_RULE') {
      const selected = String(value || '').toUpperCase();
      return `<label>진단유형
        <select class="criteria-type-select" data-value-key="${escapeHtml(key)}">
          <option value="" ${selected ? '' : 'selected'}>선택하세요</option>
          <option value="YN" ${selected === 'YN' ? 'selected' : ''}>YN · 여부</option>
          <option value="RNG" ${selected === 'RNG' ? 'selected' : ''}>RNG · 범위</option>
          <option value="FRM" ${selected === 'FRM' ? 'selected' : ''}>FRM · 일반 형식/정규식</option>
          <option value="DTM" ${selected === 'DTM' ? 'selected' : ''}>DTM · 날짜</option>
          <option value="NO" ${selected === 'NO' ? 'selected' : ''}>NO · 번호</option>
          <option value="NN" ${selected === 'NN' ? 'selected' : ''}>NN · 필수값</option>
        </select>
        <small>결과보고서에는 진단유형이 없어 사용자가 직접 선택해야 합니다.</small>
      </label>`;
    }
    const readonly = key === 'wdqId';
    const control = String(value || '').length > 100 || /sql|expression/i.test(key)
      ? `<textarea data-value-key="${escapeHtml(key)}" ${readonly ? 'disabled' : ''}>${escapeHtml(value)}</textarea>`
      : `<input data-value-key="${escapeHtml(key)}" value="${escapeHtml(value)}" ${readonly ? 'disabled' : ''}>`;
    return `<label>${escapeHtml(key)}${control}</label>`;
  }).join('');
  $('#criteria-dialog').showModal();
});

$('#criteria-select-filtered').addEventListener('change', event => {
  const type = $('#criteria-type').value;
  const validation = $('#criteria-validation-filter').value;
  const keyword = $('#criteria-search').value.trim().toLowerCase();
  const rows = managedCriteria.filter(item => (!type || item.dataType === type)
    && (!validation || (validation === 'ERROR') === criteriaErrorKeys.has(item.logicalKey))
    && (!keyword || item.logicalKey.toLowerCase().includes(keyword)
      || JSON.stringify(item.values).toLowerCase().includes(keyword)));
  for (const item of rows) {
    if (event.target.checked) selectedCriteriaIds.add(item.id); else selectedCriteriaIds.delete(item.id);
  }
  renderManagedCriteria();
});

$('#criteria-bulk-button').addEventListener('click', () => {
  const selected = managedCriteria.filter(item => selectedCriteriaIds.has(item.id));
  if (!selected.length) return;
  const fields = selected.map(item => Object.keys(item.values).filter(key => key !== 'wdqId'))
    .reduce((common, keys) => common.filter(key => keys.includes(key)));
  if (!fields.length) return toast('선택 항목에 공통으로 존재하는 수정 필드가 없습니다.', true);
  $('#bulk-field').innerHTML = fields.map(field => `<option value="${escapeHtml(field)}">${escapeHtml(field)}</option>`).join('');
  $('#bulk-selection-summary').textContent = `${selected.length.toLocaleString()}건을 일괄 수정합니다.`;
  $('#bulk-mode').value = 'PREPEND';
  $('#bulk-find').value = '';
  $('#bulk-value').value = '';
  $('#bulk-find-label').hidden = true;
  $('#criteria-bulk-dialog').showModal();
});

$('#bulk-mode').addEventListener('change', event => {
  $('#bulk-find-label').hidden = event.target.value !== 'REPLACE';
});

$('#apply-criteria-bulk').addEventListener('click', async event => {
  const project = Number($('#manage-project').value);
  const ids = [...selectedCriteriaIds];
  if (!project || !ids.length) return;
  const button = event.currentTarget;
  button.disabled = true;
  try {
    const result = await api(`/api/projects/${project}/criteria/bulk`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ itemIds: ids, field: $('#bulk-field').value, mode: $('#bulk-mode').value,
        find: $('#bulk-find').value, value: $('#bulk-value').value })
    });
    $('#criteria-bulk-dialog').close();
    ddlValidationProject = 0;
    await loadManagedCriteria();
    await loadProjects();
    toast(`${result.length.toLocaleString()}건을 일괄 수정했습니다.`);
  } catch (error) { toast(error.message, true); }
  finally { button.disabled = false; }
});

async function deleteCriteria(itemId) {
  const item = managedCriteria.find(row => row.id === itemId);
  if (!item) return;
  try {
    const usage = await api(`/api/projects/${item.projectId}/criteria/${itemId}/usage`);
    if (!usage.canDelete) {
      const shown = usage.references.slice(0, 50);
      $('#criteria-usage-title').textContent = item.values.ruleName || item.logicalKey;
      $('#criteria-usage-count').innerHTML = `연결된 사용처 <strong>${usage.referenceCount.toLocaleString()}건</strong>`;
      $('#criteria-usage-list').innerHTML = shown.map(ref => `<div class="criteria-usage-row">
        <span class="criteria-usage-relation">${escapeHtml(ref.relation)}</span>
        <div class="criteria-usage-location">${escapeHtml(ref.location)}
          <small>${escapeHtml(ref.dataType)}</small></div></div>`).join('')
        + (usage.referenceCount > shown.length
          ? `<div class="criteria-usage-more">외 ${(usage.referenceCount - shown.length).toLocaleString()}건의 사용처가 있습니다.</div>` : '');
      $('#criteria-usage-dialog').showModal();
      return;
    }
    if (!confirm(`이 진단기준을 삭제할까요?\n${item.logicalKey}`)) return;
    await api(`/api/projects/${item.projectId}/criteria/${itemId}`, { method: 'DELETE' });
    await loadManagedCriteria(); await loadProjects(); toast('진단기준을 삭제했습니다.');
  } catch (error) { toast(error.message, true); }
}

const criteriaTemplates = {
  EXCLUSION: ['dbmsOriginal','schemaOriginal','tableOriginal','columnOriginal','exclusionType','reason'],
  VERIFICATION_RULE: ['ruleName','expression','qualityIndicator'],
  CODE_RULE: ['ruleName','codeType','lookupSql','description','exclusiveYn'],
  CODE_VALUE: ['ruleName','codeId','codeName','exclusiveYn'],
  COLUMN_MAPPING: ['dbmsOriginal','schemaOriginal','tableOriginal','columnOriginal','ruleType','ruleName'],
  BUSINESS_RULE: ['ruleName','dbmsOriginal','schemaOriginal','tableOriginal','columnOriginal','ruleSql','countSql','ruleKind']
};

function renderNewCriteriaFields() {
  const type = $('#new-criteria-type').value;
  const keys = criteriaTemplates[type] || [];
  const codeRules = managedCriteria.filter(item => item.dataType === 'CODE_RULE')
    .map(item => item.values.ruleName).filter(Boolean);
  $('#new-criteria-fields').innerHTML = keys.map(key => {
    if (type === 'CODE_VALUE' && key === 'ruleName')
      return `<label>코드규칙<select data-new-key="${key}"><option value="">선택</option>${codeRules.map(name =>
        `<option value="${escapeHtml(name)}">${escapeHtml(name)}</option>`).join('')}</select></label>`;
    if (type === 'CODE_RULE' && key === 'codeType')
      return `<label>코드유형<select data-new-key="${key}"><option value="공통코드">공통코드 (CC)</option><option value="목록성코드">목록성코드 (LC)</option></select></label>`;
    if (key === 'exclusiveYn')
      return `<label>제외 여부<select data-new-key="${key}"><option value="Y">Y</option><option value="N">N</option></select></label>`;
    return `<label>${key}${/sql|expression/i.test(key) ? `<textarea data-new-key="${key}"></textarea>` : `<input data-new-key="${key}">`}</label>`;
  }).join('');
}
$('#new-criteria-type').addEventListener('change', renderNewCriteriaFields);
$('#criteria-create-button').addEventListener('click', () => {
  if (!$('#manage-project').value) return toast('프로젝트를 먼저 선택하세요.', true);
  $('#new-criteria-key').value = ''; renderNewCriteriaFields(); $('#criteria-create-dialog').showModal();
});
$('#code-value-excel-button').addEventListener('click', () => {
  if (!$('#manage-project').value) return toast('프로젝트를 먼저 선택하세요.', true);
  const rules = managedCriteria.filter(item => item.dataType === 'CODE_RULE'
    && ['LC', '목록성코드'].includes(String(item.values.codeType || '').trim()));
  if (!rules.length) return toast('업로드할 목록성코드(LC) 규칙이 없습니다.', true);
  $('#code-value-rule').innerHTML = rules.map(item =>
    `<option value="${escapeHtml(item.values.ruleName)}">${escapeHtml(item.values.ruleName)}</option>`).join('');
  $('#code-value-excel-form').reset();
  $('#code-value-excel-dialog').showModal();
});
$('#code-value-excel-form').addEventListener('submit', async event => {
  event.preventDefault();
  const project = Number($('#manage-project').value);
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    const result = await api(`/api/projects/${project}/criteria/code-values/import`, {
      method: 'POST', body: new FormData(event.currentTarget)
    });
    $('#code-value-excel-dialog').close();
    await loadManagedCriteria(); await loadProjects();
    toast(`${result.ruleName} 코드 데이터 ${Number(result.importedCount).toLocaleString()}건을 등록했습니다.`);
  } catch (error) { toast(error.message, true); }
  finally { button.disabled = false; }
});
$('#create-criteria').addEventListener('click', async () => {
  const project = Number($('#manage-project').value);
  const values = Object.fromEntries([...document.querySelectorAll('[data-new-key]')].map(input => [input.dataset.newKey, input.value]));
  const type = $('#new-criteria-type').value;
  const logicalKey = $('#new-criteria-key').value.trim()
    || (type === 'CODE_VALUE' ? `${values.ruleName || ''}|${values.codeId || ''}` : '');
  if (type === 'CODE_VALUE' && (!values.ruleName || !values.codeId))
    return toast('코드규칙과 코드값을 입력하세요.', true);
  const button = $('#create-criteria'); button.disabled = true;
  try {
    await api(`/api/projects/${project}/criteria`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dataType: $('#new-criteria-type').value, logicalKey, values }) });
    $('#criteria-create-dialog').close(); await loadManagedCriteria(); await loadProjects(); toast('진단기준을 추가했습니다.');
  } catch (error) { toast(error.message, true); }
  finally { button.disabled = false; }
});

$('#criteria-history-button').addEventListener('click', async () => {
  const project = Number($('#manage-project').value);
  if (!project) return toast('프로젝트를 먼저 선택하세요.', true);
  try {
    const history = await api(`/api/projects/${project}/criteria/history`);
    $('#history-list').innerHTML = history.length ? history.map(item => `<tr><td>${new Date(item.changedAt).toLocaleString('ko-KR')}</td><td>${escapeHtml(item.entityType)}</td><td>${escapeHtml(item.action)}</td><td>${escapeHtml(item.entityId)}</td></tr>`).join('') : '<tr><td colspan="4" class="project-empty">변경 이력이 없습니다.</td></tr>';
    $('#history-dialog').showModal();
  } catch (error) { toast(error.message, true); }
});

$('#save-criteria').addEventListener('click', async () => {
  if (!editingCriteria) return;
  const values = { ...editingCriteria.values };
  for (const control of document.querySelectorAll('#edit-fields [data-value-key]'))
    if (!control.disabled) values[control.dataset.valueKey] = control.value;
  if (editingCriteria.dataType === 'VERIFICATION_RULE'
      && editingCriteria.values.ruleOrigin === 'ADDITIONAL_EXTRACTED'
      && !['YN', 'RNG', 'FRM', 'DTM', 'NO', 'NN'].includes(String(values.ruleType || '').toUpperCase()))
    return toast('진단유형을 YN, RNG, FRM, DTM, NO, NN 중에서 선택하세요.', true);
  const button = $('#save-criteria'); button.disabled = true;
  try {
    await api(`/api/projects/${editingCriteria.projectId}/criteria/${editingCriteria.id}`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ values })
    });
    $('#criteria-dialog').close(); ddlValidationProject = 0; await loadManagedCriteria(); await loadProjects();
    if (projectId === editingCriteria.projectId) {
      const refreshed = await api(`/api/projects/${projectId}`);
      renderProject(refreshed);
      setArtifactLinks(projectId);
    }
    toast('진단기준을 수정했습니다.');
  } catch (error) { toast(error.message, true); }
  finally { button.disabled = false; }
});

function setArtifactLinks(id) {
  $('#workbook-download').href = `/api/projects/${id}/artifacts/workbook`;
  $('#ddl-download').href = `/api/projects/${id}/artifacts/ddl`;
  $('#combined-sql-download').href = `/api/projects/${id}/artifacts/combined-sql`;
  $('#sql-download').href = `/api/projects/${id}/artifacts/sql`;
  $('#exe-download').href = `/api/projects/${id}/artifacts/exe`;
  $('#delete-exe-download').href = `/api/projects/${id}/artifacts/delete-exe`;
}

$('#existing-report-file').addEventListener('change', event => {
  $('#existing-report-file-name').textContent = event.target.files[0]?.name || '';
});

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

for (const zone of document.querySelectorAll('.dropzone')) {
  zone.addEventListener('dragover', event => { event.preventDefault(); zone.classList.add('drag'); });
  zone.addEventListener('dragleave', () => zone.classList.remove('drag'));
  zone.addEventListener('drop', () => zone.classList.remove('drag'));
}

loadProjects();
async function loadSystems() {
  systemRows = await api('/api/systems');
  const body = $('#system-list');
  if (body) body.innerHTML = systemRows.length ? systemRows.map(s => `<tr><td class="project-system"><strong>${escapeHtml(s.systemName)}</strong><small>${escapeHtml(s.systemCode)}</small></td><td>${escapeHtml(s.dbmsType)} · ${escapeHtml(s.dbmsPhysicalName)}</td><td>${escapeHtml(s.defaultSchema)}</td><td>${escapeHtml(s.wdqNamespace || '미설정')}</td><td>${s.projectCount}</td><td>${s.active ? '사용' : '미사용'}</td><td class="row-actions"><button class="project-open" data-system-open="${s.id}">열기</button><button class="project-open" data-system-edit="${s.id}">수정</button><button class="project-open danger-button" data-system-delete="${s.id}">삭제</button></td></tr>`).join('') : '<tr><td colspan="7" class="project-empty">등록된 시스템이 없습니다. 시스템 등록부터 시작하세요.</td></tr>';
  const select = $('#id-system-select');
  if (select) {
    const old = select.value;
    select.innerHTML = '<option value="">시스템 선택</option>' + systemRows.map(s => `<option value="${s.id}">${escapeHtml(s.systemName)} · ${escapeHtml(s.systemCode)}</option>`).join('');
    if (systemRows.some(s => String(s.id) === old)) select.value = old;
  }
  return systemRows;
}

$('#refresh-systems')?.addEventListener('click', loadSystems);
$('#system-list')?.addEventListener('click', event => {
  const deleteButton=event.target.closest('[data-system-delete]');
  if(deleteButton){deleteSystem(Number(deleteButton.dataset.systemDelete),deleteButton);return;}
  const openButton=event.target.closest('[data-system-open]'); if(openButton){openSystemWorkspace(Number(openButton.dataset.systemOpen));return;}
  const button = event.target.closest('[data-system-edit]'); if (!button) return;
  const system = systemRows.find(s => s.id === Number(button.dataset.systemEdit));
  const fields = $('#system-edit-fields'); fields.dataset.systemId = system.id; fields.dataset.mode='edit';
  for (const key of SYSTEM_FIELDS) fields.querySelector(`[name="${key}"]`).value = system[key] ?? '';
  $('#system-dialog').showModal();
});
async function deleteSystem(id,button){
  const system=systemRows.find(row=>row.id===id);if(!system)return;
  if(!confirm(`${system.systemName} 시스템을 삭제하시겠습니까?\\n\\n등록된 프로젝트가 있으면 삭제되지 않습니다.`))return;
  button.disabled=true;
  try{await api(`/api/systems/${id}`,{method:'DELETE'});if(selectedSystemId===id){selectedSystemId=null;$('#system-workspace').classList.add('locked');history.replaceState(null,'','#/systems');}await loadSystems();toast(`${system.systemName} 시스템을 삭제했습니다.`);}
  catch(error){button.disabled=false;toast(error.message,true);}
}
$('#create-system-button')?.addEventListener('click',()=>{const fields=$('#system-edit-fields');fields.dataset.mode='create';delete fields.dataset.systemId;for(const input of fields.querySelectorAll('[name]')) input.value=input.name==='active'?'true':input.name==='criteriaPrefix'?'[{year}표준시스템DB {systemName}]':'';$('#system-dialog').showModal();});
$('#save-system')?.addEventListener('click', async () => {
  const fields=$('#system-edit-fields'), id=fields.dataset.systemId, creating=fields.dataset.mode==='create';
  const values=Object.fromEntries([...fields.querySelectorAll('[name]')].map(x=>[x.name,x.value])); values.active=values.active==='true';
  try { const saved=await api(creating?'/api/systems':`/api/systems/${id}`,{method:creating?'POST':'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify(values)}); $('#system-dialog').close(); await loadSystems(); await openSystemWorkspace(saved.id); toast(creating?'공통표준 시스템을 등록했습니다.':'시스템 정보를 저장했습니다.'); } catch(e){toast(e.message,true);}
});

async function openSystemWorkspace(id, route = true){
  selectedSystemId=id; if(!projectRows.length) await loadProjects();
  showView('systems', false);
  if (route) updateRoute(`#/systems/${id}`);
  const system=systemRows.find(s=>s.id===id); $('#system-workspace').classList.remove('locked'); $('#workspace-system-name').textContent=system.systemName;
  const policyNote=$('#system-workspace [data-workspace-pane="policy"] .manager-note');
  if(policyNote) policyNote.textContent=`현재 ${system.wdqNamespace}번 채번 영역입니다. 일반 ID는 접두어 뒤 숫자가 ${system.wdqNamespace}로 시작하고, DB 연결 ID는 영역 번호를 8자리로 채웁니다.`;
  const policies=await api(`/api/systems/${id}/id-policies`);
  $('#workspace-policy-list').innerHTML=policies.map(p=>`<tr><td><strong>${p.idType}</strong></td><td><input class="table-input" data-prefix value="${escapeHtml(p.idPrefix)}"></td><td><input class="table-input small" type="number" data-width value="${p.numberWidth}"></td><td><input class="table-input" type="number" data-last value="${p.lastValue}"></td><td><code>${escapeHtml(policyExample(p,system.wdqNamespace))}</code></td><td><button class="project-open" data-workspace-policy-save="${p.idType}">저장</button></td></tr>`).join('');
  const rows=projectRows.filter(p=>p.systemCode===system.systemCode);
  $('#workspace-project-list').innerHTML=rows.length?rows.map(p=>`<tr><td>v${p.revision}</td><td>${p.targetYear} / ${p.deploymentYearMonth}</td><td>${statusLabels[p.status]||p.status}</td><td>${p.itemCount.toLocaleString()}건</td><td class="row-actions"><button class="project-open" data-workspace-project="${p.projectId}">파일 등록·산출물</button><button class="project-open danger-button" data-delete-project="${p.projectId}">삭제</button></td></tr>`).join(''):'<tr><td colspan="5" class="project-empty">프로젝트가 없습니다.</td></tr>';
  $('#workspace-output-list').innerHTML=rows.length?rows.map(p=>`<div class="summary-card"><small>v${p.revision} · ${p.deploymentYearMonth}</small><strong>${statusLabels[p.status]||p.status}</strong></div>`).join(''):'<div class="project-empty">생성된 산출물이 없습니다.</div>';
  $('#system-workspace').scrollIntoView({behavior:'smooth',block:'start'});
}
$('#workspace-system-edit')?.addEventListener('click',()=>{const s=systemRows.find(x=>x.id===selectedSystemId);if(!s)return;const fields=$('#system-edit-fields');fields.dataset.systemId=s.id;fields.dataset.mode='edit';for(const key of SYSTEM_FIELDS)fields.querySelector(`[name="${key}"]`).value=s[key]??'';$('#system-dialog').showModal();});
document.querySelector('.workspace-tabs')?.addEventListener('click',e=>{const b=e.target.closest('[data-workspace-tab]');if(!b)return;document.querySelectorAll('[data-workspace-tab]').forEach(x=>x.classList.toggle('active',x===b));document.querySelectorAll('[data-workspace-pane]').forEach(x=>x.classList.toggle('active',x.dataset.workspacePane===b.dataset.workspaceTab));});
$('#workspace-policy-list')?.addEventListener('click',async e=>{const b=e.target.closest('[data-workspace-policy-save]');if(!b)return;const row=b.closest('tr');try{await api(`/api/systems/${selectedSystemId}/id-policies/${b.dataset.workspacePolicySave}`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({idPrefix:row.querySelector('[data-prefix]').value,numberWidth:Number(row.querySelector('[data-width]').value),lastValue:Number(row.querySelector('[data-last]').value),active:true})});await openSystemWorkspace(selectedSystemId);toast('채번 정책을 저장했습니다.');}catch(err){toast(err.message,true);}});
$('#workspace-project-form')?.addEventListener('submit',async e=>{e.preventDefault();if(!selectedSystemId)return;const values=Object.fromEntries(new FormData(e.currentTarget));values.targetYear=Number(values.targetYear);try{const created=await api(`/api/systems/${selectedSystemId}/projects`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(values)});await loadProjects();await openSystemWorkspace(selectedSystemId);toast(`프로젝트 #${created.projectId}를 생성했습니다.`);}catch(err){toast(err.message,true);}});
$('#workspace-project-list')?.addEventListener('click',e=>{const deleteButton=e.target.closest('[data-delete-project]');if(deleteButton){deleteProject(Number(deleteButton.dataset.deleteProject),deleteButton);return;}const b=e.target.closest('[data-workspace-project]');if(b)openProject(Number(b.dataset.workspaceProject));});

function policyExample(policy, namespace) {
  const next=policy.lastValue+1, ns=namespace||'';
  if(policy.idType==='DB_CONNECTION' && ns) return policy.idPrefix+String(ns).padStart(policy.numberWidth,'0');
  return policy.idPrefix+ns+String(next).padStart(Math.max(1,policy.numberWidth-ns.length),'0');
}
async function loadIdPolicies(){
  const systemId=Number($('#id-system-select')?.value); if(!systemId){if($('#id-policy-list')) $('#id-policy-list').innerHTML='<tr><td colspan="7" class="project-empty">시스템을 선택하세요.</td></tr>';return;}
  const policies=await api(`/api/systems/${systemId}/id-policies`), system=systemRows.find(s=>s.id===systemId), keyword=$('#id-search').value.toLowerCase();
  $('#id-policy-list').innerHTML=policies.filter(p=>p.idType.toLowerCase().includes(keyword)).map(p=>`<tr><td><strong>${p.idType}</strong></td><td><input class="table-input" data-prefix value="${escapeHtml(p.idPrefix)}"></td><td><input class="table-input small" type="number" data-width value="${p.numberWidth}"></td><td><input class="table-input" type="number" data-last value="${p.lastValue}"></td><td><code>${escapeHtml(policyExample(p,system.wdqNamespace))}</code></td><td>${p.active?'사용':'중지'}</td><td><button class="project-open" data-policy-save="${p.idType}">저장</button></td></tr>`).join('');
}
$('#id-system-select')?.addEventListener('change',loadIdPolicies); $('#id-search')?.addEventListener('input',loadIdPolicies);
$('#id-policy-list')?.addEventListener('click',async event=>{const b=event.target.closest('[data-policy-save]');if(!b)return;const row=b.closest('tr'),systemId=$('#id-system-select').value;try{await api(`/api/systems/${systemId}/id-policies/${b.dataset.policySave}`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({idPrefix:row.querySelector('[data-prefix]').value,numberWidth:Number(row.querySelector('[data-width]').value),lastValue:Number(row.querySelector('[data-last]').value),active:true})});await loadIdPolicies();toast('채번 정책을 저장했습니다.');}catch(e){toast(e.message,true);}});

async function loadArtifactHistory(){
  const id=Number($('#artifact-project-select')?.value); if(!id){if($('#artifact-history-list'))$('#artifact-history-list').innerHTML='<tr><td colspan="5" class="project-empty">프로젝트를 선택하세요.</td></tr>';return;}
  try{const rows=await api(`/api/projects/${id}/artifacts/history`);$('#artifact-history-list').innerHTML=rows.length?rows.map(x=>`<tr><td>${new Date(x.createdAt).toLocaleString('ko-KR')}</td><td>${escapeHtml(x.artifactKind)}</td><td>${escapeHtml(x.fileName)}</td><td>${Number(x.byteSize).toLocaleString()} B</td><td><code title="${x.sha256}">${x.sha256.slice(0,16)}…</code></td></tr>`).join(''):'<tr><td colspan="5" class="project-empty">생성 이력이 없습니다.</td></tr>';}catch(e){toast(e.message,true);}
}
$('#artifact-project-select')?.addEventListener('change',event=>{setCurrentProject(event.target.value);loadArtifactHistory();});

function sqlMetric(label, value, tone = '') {
  return `<div class="summary-card ${tone}"><small>${label}</small><strong>${Number(value).toLocaleString()}</strong></div>`;
}

function renderSqlComparison(result) {
  $('#sql-comparison-empty').hidden = true;
  $('#sql-comparison-result').hidden = false;
  $('#sql-baseline-meta').innerHTML = `<strong>${escapeHtml(result.baselineName)}</strong><span>등록 ${new Date(result.uploadedAt).toLocaleString('ko-KR')}</span>`;
  $('#sql-comparison-summary').innerHTML =
    sqlMetric('2025 데이터 행', result.baselineRowCount) +
    sqlMetric('현재 데이터 행', result.currentRowCount) +
    sqlMetric('동일', result.unchangedCount, 'same') +
    sqlMetric('값 변경', result.changedCount, 'changed') +
    sqlMetric('2025에만', result.baselineOnlyCount, 'removed') +
    sqlMetric('현재에만', result.currentOnlyCount, 'added');
  const body = $('#sql-comparison-list');
  if (!result.tableDifferences.length) {
    body.innerHTML = '<tr><td colspan="8" class="project-empty">WDQ 적재 데이터 행 기준으로 달라진 부분이 없습니다.</td></tr>';
    return;
  }
  body.innerHTML = result.tableDifferences.map((row, index) => {
    const changed = row.changedRows.map(change => `<article class="sql-value-change"><strong>${escapeHtml(change.logicalKey)}</strong>${change.differences.map(diff => `<div><code>${escapeHtml(diff.column)}</code><span class="old">${escapeHtml(diff.baselineValue)}</span><span class="new">${escapeHtml(diff.currentValue)}</span></div>`).join('')}</article>`).join('') || '<p>없음</p>';
    const oldSql = row.baselineOnlySamples.map(sample => `<div class="sql-row-sample"><strong>${escapeHtml(sample.logicalKey)}</strong><pre>${escapeHtml(sample.sql)}</pre></div>`).join('') || '<p>없음</p>';
    const newSql = row.currentOnlySamples.map(sample => `<div class="sql-row-sample"><strong>${escapeHtml(sample.logicalKey)}</strong><pre>${escapeHtml(sample.sql)}</pre></div>`).join('') || '<p>없음</p>';
    return `<tr><td><code>${escapeHtml(row.tableName)}</code></td><td>${row.baselineCount}</td><td>${row.currentCount}</td><td>${row.unchangedCount}</td><td class="diff-changed">${row.changedCount}</td><td class="diff-removed">${row.baselineOnlyCount}</td><td class="diff-added">${row.currentOnlyCount}</td><td><button class="project-open" type="button" data-sql-diff="${index}">보기</button></td></tr>
      <tr class="sql-diff-detail" data-sql-diff-detail="${index}" hidden><td colspan="8"><section class="sql-changed-section"><h4>값이 변경된 행</h4>${changed}</section><div class="sql-diff-columns"><section><h4>2025에만 존재</h4>${oldSql}</section><section><h4>현재에만 존재</h4>${newSql}</section></div><small>각 구분별 최대 10개 행을 표시합니다.</small></td></tr>`;
  }).join('');
}

function renderSqlBaselineAsset(info) {
  const box = $('#sql-baseline-asset');
  if (!info) {
    box.className = 'sql-baseline-asset empty';
    box.innerHTML = '<div><strong>등록된 2025 기준 SQL 없음</strong><small>선택한 프로젝트 전용 파일로 서버에 저장됩니다.</small></div>';
    return;
  }
  box.className = 'sql-baseline-asset';
  box.innerHTML = `<div><strong>${escapeHtml(info.originalName)}</strong><small>${Number(info.byteSize).toLocaleString()} B · ${new Date(info.uploadedAt).toLocaleString('ko-KR')} · SHA-256 ${escapeHtml(info.sha256.slice(0, 16))}…</small></div>
    <div class="sql-baseline-actions"><a href="/api/projects/${info.projectId}/sql-comparison/baseline/file">다운로드</a><button type="button" class="delete" data-delete-sql-baseline="${info.projectId}">삭제</button></div>`;
}

async function loadSqlComparison() {
  const id = Number($('#sql-comparison-project')?.value || projectId);
  if (!id) {
    renderSqlBaselineAsset(null);
    $('#sql-comparison-result').hidden = true;
    $('#sql-comparison-empty').hidden = false;
    $('#sql-comparison-empty').textContent = '프로젝트를 선택하고 2025년 SQL을 등록해주세요.';
    return;
  }
  setCurrentProject(id);
  try {
    renderSqlBaselineAsset(await api(`/api/projects/${id}/sql-comparison/baseline`));
  } catch (error) {
    renderSqlBaselineAsset(null);
    $('#sql-comparison-result').hidden = true;
    $('#sql-comparison-empty').hidden = false;
    $('#sql-comparison-empty').textContent = error.status === 404
      ? '등록된 2025 기준 SQL이 없습니다. SQL 파일을 업로드하면 즉시 비교합니다.'
      : error.message;
    return;
  }
  try {
    renderSqlComparison(await api(`/api/projects/${id}/sql-comparison`));
  } catch (error) {
    $('#sql-comparison-result').hidden = true;
    $('#sql-comparison-empty').hidden = false;
    $('#sql-comparison-empty').textContent = error.message;
  }
}

$('#sql-comparison-project')?.addEventListener('change', event => {
  setCurrentProject(event.target.value);
  loadSqlComparison();
});
$('#sql-baseline-file')?.addEventListener('change', event => {
  $('#sql-baseline-file-name').textContent = event.target.files[0]?.name || '2025 SQL 파일 선택';
});
$('#sql-baseline-form')?.addEventListener('submit', async event => {
  event.preventDefault();
  const id = Number($('#sql-comparison-project').value || projectId);
  const file = $('#sql-baseline-file').files[0];
  if (!id) return toast('비교할 프로젝트를 선택해주세요.', true);
  if (!file) return toast('2025 기준 SQL 파일을 선택해주세요.', true);
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    const form = new FormData();
    form.append('file', file);
    renderSqlComparison(await api(`/api/projects/${id}/sql-comparison/baseline`, { method: 'POST', body: form }));
    renderSqlBaselineAsset(await api(`/api/projects/${id}/sql-comparison/baseline`));
    toast('2025 기준 SQL을 프로젝트 서버 파일로 저장하고 현재 SQL과 비교했습니다.');
  } catch (error) {
    toast(error.message, true);
  } finally {
    button.disabled = false;
  }
});
$('#sql-baseline-asset')?.addEventListener('click', async event => {
  const button = event.target.closest('[data-delete-sql-baseline]');
  if (!button) return;
  const id = Number(button.dataset.deleteSqlBaseline);
  if (!confirm('이 프로젝트에 저장된 2025 기준 SQL을 삭제하시겠습니까?\\n비교 결과도 더 이상 표시되지 않습니다.')) return;
  button.disabled = true;
  try {
    await api(`/api/projects/${id}/sql-comparison/baseline`, { method: 'DELETE' });
    $('#sql-baseline-file').value = '';
    $('#sql-baseline-file-name').textContent = '2025 SQL 파일 선택';
    renderSqlBaselineAsset(null);
    $('#sql-comparison-result').hidden = true;
    $('#sql-comparison-empty').hidden = false;
    $('#sql-comparison-empty').textContent = '등록된 2025 기준 SQL이 없습니다. SQL 파일을 업로드하면 즉시 비교합니다.';
    toast('프로젝트의 2025 기준 SQL을 삭제했습니다.');
  } catch (error) {
    button.disabled = false;
    toast(error.message, true);
  }
});
$('#sql-comparison-list')?.addEventListener('click', event => {
  const button = event.target.closest('[data-sql-diff]');
  if (!button) return;
  const detail = document.querySelector(`[data-sql-diff-detail="${button.dataset.sqlDiff}"]`);
  detail.hidden = !detail.hidden;
  button.textContent = detail.hidden ? '보기' : '닫기';
});

async function restoreRoute() {
  const route = window.location.hash || '#/systems';
  const projectMatch = route.match(/^#\/(?:results|projects)\/(\d+)$/);
  const systemMatch = route.match(/^#\/systems\/(\d+)$/);
  await loadSystems();
  await loadProjects();
  if (projectMatch) return openProject(Number(projectMatch[1]), false);
  if (systemMatch) return openSystemWorkspace(Number(systemMatch[1]), false);
  const view = Object.entries(VIEW_ROUTES).find(([, hash]) => hash === route)?.[0] || 'systems';
  showView(view, false);
  if (!window.location.hash) history.replaceState(null, '', VIEW_ROUTES.systems);
}
window.addEventListener('popstate', () => restoreRoute().catch(error => toast(error.message, true)));
restoreRoute().catch(error => toast(error.message, true));
