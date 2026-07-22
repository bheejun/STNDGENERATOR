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
    $('#result-panel').classList.remove('locked');
    $('#step-2').classList.remove('active');
    $('#step-3').classList.add('active');
    $('#workbook-download').href = `/api/projects/${projectId}/artifacts/workbook`;
    $('#sql-download').href = `/api/projects/${projectId}/artifacts/sql`;
    $('#workbook-download').classList.remove('disabled');
    $('#sql-download').classList.remove('disabled');
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

for (const zone of document.querySelectorAll('.dropzone')) {
  zone.addEventListener('dragover', event => { event.preventDefault(); zone.classList.add('drag'); });
  zone.addEventListener('dragleave', () => zone.classList.remove('drag'));
  zone.addEventListener('drop', () => zone.classList.remove('drag'));
}
