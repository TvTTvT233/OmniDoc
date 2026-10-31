import request from '../utils/request'
import axios from 'axios'
import { STORAGE_KEYS } from '../constants'

const ROOT = '/review-m0/cases'
const id = value => encodeURIComponent(String(value))
const path = (caseId, suffix = '') => `${ROOT}/${id(caseId)}${suffix}`
const data = result => result?.data ?? result
const get = async url => data(await request.get(url))
const write = async (method, url, body, key) => data(await request({
  method, url, data: body, headers: { 'Idempotency-Key': key },
}))

export function reviewError(error) {
  const payload = error?.response?.data
  const code = payload?.errorCode || error?.code || 'REQUEST_FAILED'
  const messages = {
    REVIEW_M0_VERSION_CONFLICT: '内容已在其他位置更新，请刷新后核对再提交。当前输入已保留。',
    REVIEW_M0_CHECKLIST_STALE_BASIS: '官方依据已变化，请重新核对并确认清单。',
    REVIEW_M0_CHECKLIST_NOT_CONFIRMED: '请先确认并发布正式清单。',
    REVIEW_M0_UPLOAD_NOT_CONFIRMED: '请确认当前这轮材料已经上传完毕。',
    REVIEW_M0_CONDITIONAL_UNRESOLVED: '请为每个启用项目明确选择是否适用。',
    REVIEW_M0_BASIS_CANNOT_CONFIRM: '官方依据存在无法处理的输入，请查看诊断并替换或移除。',
    REVIEW_M0_INCREMENTAL_BASE_MISMATCH: '变化范围不符合单文件复审条件，请运行完整审查。',
    REVIEW_M0_UNSUPPORTED_FILE_TYPE: '仅支持 PDF、DOCX、XLSX、JPG、PNG、ZIP。',
    REVIEW_M0_RESOURCE_NOT_FOUND: '资源不存在或当前账号无权访问。',
    REVIEW_M0_CASE_NOT_FOUND: '案例不存在或当前账号无权访问。',
    AUTHENTICATION_REQUIRED: '登录已失效，请重新登录。',
    REVIEW_M0_STORAGE_UNAVAILABLE: '审查存储暂不可用，请稍后重试。',
    REVIEW_M0_FILE_SERVICE_UNAVAILABLE: '文件服务暂不可用，请稍后重试。',
    REVIEW_M0_REQUEST_INVALID: '提交内容不符合要求，请检查名称、规则及必填选项。',
  }
  return { code, message: messages[code] || '操作未完成，请保留当前输入并重试。', requestId: payload?.requestId }
}

export const reviewM0Api = {
  listFiles: async page => data(await request.get('/files/owned', { params: { page, size: 20 } })),
  uploadFile: async file => {
    const body = new FormData(); body.append('file', file)
    return data(await request.post('/files/owned', body))
  },
  list: async cursor => data(await request.get(ROOT, { params: { limit: 20, ...(cursor ? { cursor } : {}) } })),
  create: (title, key) => write('post', ROOT, { title, processingMode: 'LOCAL_ONLY' }, key),
  case: caseId => get(path(caseId)),
  bases: caseId => get(path(caseId, '/official-bases')),
  attachBasis: (caseId, body, key) => write('post', path(caseId, '/official-bases'), body, key),
  classifyBasis: (caseId, basisId, body, key) => write('patch', path(caseId, `/official-bases/${id(basisId)}`), body, key),
  detachBasis: (caseId, basisId, version, key) => write('delete', path(caseId, `/official-bases/${id(basisId)}`), { expectedVersion: version }, key),
  draft: async caseId => {
    try { return data(await request.get(path(caseId, '/checklist-draft'), { allowEmptyReviewDraft: true })) }
    catch (error) {
      if (error.response?.status === 404 && error.response?.data?.errorCode === 'REVIEW_M0_RESOURCE_NOT_FOUND') return null
      throw error
    }
  },
  addRequirement: (caseId, body, key) => write('post', path(caseId, '/checklist-draft/requirements'), body, key),
  editRequirement: (caseId, requirementId, body, key) => write('patch', path(caseId, `/checklist-draft/requirements/${id(requirementId)}`), body, key),
  publish: (caseId, body, key) => write('post', path(caseId, '/checklist-snapshots'), body, key),
  snapshot: (caseId, snapshotId) => get(path(caseId, `/checklist-snapshots/${id(snapshotId)}`)),
  sources: caseId => get(path(caseId, '/submission-sources')),
  attachSource: (caseId, body, key) => write('post', path(caseId, '/submission-sources'), body, key),
  removeSource: (caseId, sourceId, version, key) => write('delete', path(caseId, `/submission-sources/${id(sourceId)}`), { expectedVersion: version }, key),
  materials: caseId => get(path(caseId, '/materials')),
  rename: (caseId, materialId, body, key) => write('patch', path(caseId, `/materials/${id(materialId)}`), body, key),
  assignments: caseId => get(path(caseId, '/assignments')),
  saveAssignments: (caseId, body, key) => write('put', path(caseId, '/assignments'), body, key),
  confirmUpload: (caseId, body, key) => write('post', path(caseId, '/upload-confirmations'), body, key),
  review: (caseId, body, key) => write('post', path(caseId, '/reviews'), body, key),
  job: (caseId, jobId) => get(path(caseId, `/jobs/${id(jobId)}`)),
  cancel: (caseId, jobId, key) => write('post', path(caseId, `/jobs/${id(jobId)}/cancel`), undefined, key),
  report: (caseId, reportId) => get(path(caseId, `/reports/${id(reportId)}`)),
  ignore: (caseId, reportId, findingId, reason, key) => write('post', path(caseId, `/reports/${id(reportId)}/findings/${id(findingId)}/decisions`), { decision: 'IGNORE', reason: reason || null }, key),
  async download(caseId, materialId) {
    const token = localStorage.getItem(STORAGE_KEYS.ACCESS_TOKEN)
    const response = await axios.get(`/api${path(caseId, `/materials/${id(materialId)}/download`)}`, {
      responseType: 'arraybuffer', headers: token ? { Authorization: `Bearer ${token}` } : {}, timeout: 300000,
    })
    const expected = response.headers['x-content-sha256']
    if (!/^[a-f0-9]{64}$/.test(expected || '') || !globalThis.crypto?.subtle) throw new Error('DOWNLOAD_INTEGRITY_UNAVAILABLE')
    const digest = await crypto.subtle.digest('SHA-256', response.data)
    const actual = [...new Uint8Array(digest)].map(n => n.toString(16).padStart(2, '0')).join('')
    if (actual !== expected) throw new Error('DOWNLOAD_INTEGRITY_MISMATCH')
    const disposition = response.headers['content-disposition'] || ''
    const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1]
    if (!encoded) throw new Error('DOWNLOAD_FILENAME_MISSING')
    const name = decodeURIComponent(encoded)
    if (!name || /[/\\\x00-\x1f]/.test(name)) throw new Error('DOWNLOAD_FILENAME_INVALID')
    return { blob: new Blob([response.data], { type: response.headers['content-type'] }), name }
  },
}
