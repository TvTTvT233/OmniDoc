import fs from 'node:fs'
import path from 'node:path'
import net from 'node:net'
import crypto from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(scriptDir, '..')
const gatewayBase = process.env.OMNIDOC_GATEWAY_URL || 'http://127.0.0.1:8080'
const webBase = process.env.OMNIDOC_WEB_URL || 'http://127.0.0.1:5173'
const strict = process.argv.includes('--strict')
const runId = new Date().toISOString().replace(/\D/g, '').slice(0, 14)
const shortId = runId.slice(6)
const model = 'qwen3.7-max-2026-05-20'
const timeoutMs = 300_000

const owner = {
  username: `od_owner_${shortId}`,
  password: 'OmniDoc@Test123',
  email: `od_owner_${shortId}@example.com`,
  phone: `139${shortId.slice(-8).padStart(8, '0')}`
}
const collaborator = {
  username: `od_collab_${shortId}`,
  password: 'OmniDoc@Test123',
  email: `od_collab_${shortId}@example.com`,
  phone: `138${shortId.slice(-8).padStart(8, '1')}`
}

const fixtureText = [
  'OmniDoc 功能测试文档。',
  '项目支持用户认证、文件管理、文档编辑、AI 摘要、RAG 检索和 Agent 工作台。',
  '唯一测试标识：' + runId,
  '验收关键词：海棠、知识库、协作。'
].join('\n')

const results = []
const cleanup = {
  fileIds: new Set(),
  documentIds: new Set(),
  ragDocumentIds: new Set(),
  knowledgeJobIds: new Set(),
  agentTraceIds: new Set(),
  conversationIds: new Set(),
  aiObjects: [],
  usernames: [owner.username, collaborator.username]
}

let ownerUser
let collaboratorUser
let ownerToken
let ownerRefreshToken
let collaboratorToken
let adminToken
let primaryFileId
let documentFileId
let documentId
let commentId
let annotationId
let ragDocumentId
let asyncKnowledgeJobId
let aiFile
let skillFile
let conversationId

function redact(value) {
  const secretKeys = /^(accessToken|refreshToken|password|authorization|apiKey|confirmationToken)$/i
  if (Array.isArray(value)) return value.map(redact)
  if (value && typeof value === 'object' && !Buffer.isBuffer(value)) {
    const output = {}
    for (const [key, item] of Object.entries(value)) {
      output[key] = secretKeys.test(key) ? '<redacted>' : redact(item)
    }
    return output
  }
  if (typeof value === 'string') {
    return value
      .replace(/Bearer\s+[A-Za-z0-9._~-]+/gi, 'Bearer <redacted>')
      .replace(/\beyJ[A-Za-z0-9._~-]{20,}\b/g, '<redacted-jwt>')
  }
  return value
}

function excerpt(value, max = 900) {
  let text
  if (Buffer.isBuffer(value)) {
    text = JSON.stringify({
      binaryBytes: value.length,
      sha256: crypto.createHash('sha256').update(value).digest('hex')
    })
  } else if (typeof value === 'string') {
    text = value
  } else {
    try {
      text = JSON.stringify(redact(value))
    } catch {
      text = String(value)
    }
  }
  text = text.replace(/\s+/g, ' ').trim()
  return text.length > max ? text.slice(0, max) + '…' : text
}

function headersObject(headers) {
  return Object.fromEntries([...headers.entries()].map(([key, value]) => [key.toLowerCase(), value]))
}

async function http(target, options = {}) {
  const {
    base = gatewayBase,
    method = 'GET',
    token,
    json,
    text,
    form,
    responseType = 'auto',
    headers = {}
  } = options
  const requestHeaders = { ...headers }
  if (token) requestHeaders.Authorization = `Bearer ${token}`
  let body
  if (json !== undefined) {
    requestHeaders['Content-Type'] = 'application/json'
    body = JSON.stringify(json)
  } else if (text !== undefined) {
    requestHeaders['Content-Type'] = requestHeaders['Content-Type'] || 'text/plain; charset=utf-8'
    body = text
  } else if (form !== undefined) {
    body = form
  }
  const startedAt = performance.now()
  const response = await fetch(new URL(target, base), {
    method,
    headers: requestHeaders,
    body,
    signal: AbortSignal.timeout(timeoutMs)
  })
  const elapsedMs = Math.round(performance.now() - startedAt)
  const responseHeaders = headersObject(response.headers)
  const contentType = responseHeaders['content-type'] || ''
  let responseBody
  if (responseType === 'buffer') {
    responseBody = Buffer.from(await response.arrayBuffer())
  } else if (responseType === 'text') {
    responseBody = await response.text()
  } else if (contentType.includes('application/json')) {
    const raw = await response.text()
    try {
      responseBody = raw ? JSON.parse(raw) : null
    } catch {
      responseBody = raw
    }
  } else {
    responseBody = await response.text()
  }
  return {
    status: response.status,
    headers: responseHeaders,
    body: responseBody,
    elapsedMs
  }
}

function successResult(response) {
  return response.status === 200 &&
    response.body &&
    typeof response.body === 'object' &&
    response.body.code === 200
}

function expectedFailure(response) {
  return response.status >= 400 ||
    (response.body && typeof response.body === 'object' &&
      response.body.code !== undefined && response.body.code !== 200)
}

function checkResult(pass, detail = '') {
  return { pass: Boolean(pass), detail }
}

async function testCase(definition, action, validate = successResult) {
  const startedAt = performance.now()
  try {
    const response = await action()
    const checked = validate(response)
    const verdict = typeof checked === 'boolean' ? { pass: checked, detail: '' } : checked
    const status = verdict.pass ? 'PASS' : 'FAIL'
    const result = {
      id: definition.id,
      module: definition.module,
      name: definition.name,
      target: definition.target,
      input: definition.input,
      expected: definition.expected,
      status,
      anomaly: status === 'FAIL' ? '是' : '否',
      httpStatus: response?.status ?? null,
      businessCode: response?.body && typeof response.body === 'object'
        ? response.body.code ?? null
        : null,
      elapsedMs: response?.elapsedMs ?? Math.round(performance.now() - startedAt),
      result: excerpt(response?.body),
      detail: verdict.detail || ''
    }
    results.push(result)
    console.log(`[${status}] ${result.id} ${result.name} (${result.elapsedMs} ms)`)
    return { response, pass: verdict.pass }
  } catch (error) {
    const result = {
      id: definition.id,
      module: definition.module,
      name: definition.name,
      target: definition.target,
      input: definition.input,
      expected: definition.expected,
      status: 'FAIL',
      anomaly: '是',
      httpStatus: null,
      businessCode: null,
      elapsedMs: Math.round(performance.now() - startedAt),
      result: '',
      detail: excerpt(error?.stack || error?.message || error)
    }
    results.push(result)
    console.log(`[FAIL] ${result.id} ${result.name} (${result.elapsedMs} ms)`)
    return { response: null, pass: false }
  }
}

function skipCase(definition, reason) {
  const result = {
    id: definition.id,
    module: definition.module,
    name: definition.name,
    target: definition.target,
    input: definition.input,
    expected: definition.expected,
    status: 'SKIP',
    anomaly: '未覆盖',
    httpStatus: null,
    businessCode: null,
    elapsedMs: 0,
    result: '',
    detail: reason
  }
  results.push(result)
  console.log(`[SKIP] ${result.id} ${result.name}: ${reason}`)
}

function portOpen(port, host = '127.0.0.1') {
  return new Promise((resolve) => {
    const socket = net.createConnection({ port, host })
    const finish = (open) => {
      socket.removeAllListeners()
      socket.destroy()
      resolve(open)
    }
    socket.setTimeout(2500)
    socket.once('connect', () => finish(true))
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
  })
}

function resultData(response) {
  return response?.body?.data
}

function formWithFiles(entries, fields = {}) {
  const form = new FormData()
  for (const [name, content, filename, contentType = 'text/plain'] of entries) {
    form.append(name, new Blob([content], { type: contentType }), filename)
  }
  for (const [key, value] of Object.entries(fields)) {
    if (value !== undefined && value !== null) form.append(key, String(value))
  }
  return form
}

async function pollAsyncJob(jobId, token, maxWaitMs = 300_000) {
  const deadline = Date.now() + maxWaitMs
  let last
  while (Date.now() < deadline) {
    last = await http(`/api/ai/async/jobs/${encodeURIComponent(jobId)}`, { token })
    const status = String(resultData(last)?.status || '').toLowerCase()
    if (['success', 'failed', 'error'].includes(status)) return last
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  return last
}

async function pollKnowledgeJob(jobId, token, maxWaitMs = 120_000) {
  const deadline = Date.now() + maxWaitMs
  let last
  while (Date.now() < deadline) {
    last = await http(`/api/ai/agent/knowledge/jobs/${encodeURIComponent(jobId)}`, { token })
    const status = String(resultData(last)?.status || '').toLowerCase()
    if (['completed', 'success', 'failed', 'error'].includes(status)) return last
    await new Promise((resolve) => setTimeout(resolve, 750))
  }
  return last
}

function parseEnvFile() {
  const envPath = path.join(repoRoot, '.env')
  if (!fs.existsSync(envPath)) return {}
  const output = {}
  for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const index = trimmed.indexOf('=')
    if (index < 1) continue
    const key = trimmed.slice(0, index).trim()
    let value = trimmed.slice(index + 1).trim()
    if ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    output[key] = value
  }
  return output
}

function mysql(sql) {
  const envFile = parseEnvFile()
  const password = envFile.MYSQL_ROOT_PASSWORD
  const database = envFile.MYSQL_DATABASE || 'omnidoc'
  if (!password) throw new Error('MYSQL_ROOT_PASSWORD is missing from .env')
  return execFileSync('docker', [
    'exec',
    '-e', `MYSQL_PWD=${password}`,
    'omnidoc-local-mysql-1',
    'mysql',
    '-uroot',
    '-N',
    '-B',
    '-D', database,
    '-e', sql
  ], {
    cwd: repoRoot,
    encoding: 'utf8',
    env: { ...process.env }
  }).trim()
}

function sqlString(value) {
  return `'${String(value).replaceAll("'", "''")}'`
}

async function cleanupResources() {
  for (const conversation of cleanup.conversationIds) {
    try {
      if (ownerToken) {
        await http(`/api/ai/agent/chat/${encodeURIComponent(conversation)}/end`, {
          method: 'POST',
          token: ownerToken
        })
      }
    } catch {}
  }
  for (const ragId of cleanup.ragDocumentIds) {
    try {
      if (ownerToken) {
        await http(`/api/ai/rag/document/${encodeURIComponent(ragId)}`, {
          method: 'DELETE',
          token: ownerToken
        })
      }
    } catch {}
  }
  for (const jobId of cleanup.knowledgeJobIds) {
    try {
      if (ownerToken) {
        await http(`/api/ai/agent/knowledge/jobs/${encodeURIComponent(jobId)}`, {
          method: 'DELETE',
          token: ownerToken
        })
      }
    } catch {}
  }
  for (const traceId of cleanup.agentTraceIds) {
    try {
      if (ownerToken) {
        await http(`/api/ai/agent/tasks/${encodeURIComponent(traceId)}`, {
          method: 'DELETE',
          token: ownerToken
        })
      }
    } catch {}
  }
  for (const docId of cleanup.documentIds) {
    try {
      if (ownerToken) {
        await http(`/api/documents/${encodeURIComponent(docId)}`, {
          method: 'DELETE',
          token: ownerToken
        })
      }
    } catch {}
  }
  for (const fileId of cleanup.fileIds) {
    try {
      if (ownerToken) {
        await http(`/api/files/${encodeURIComponent(fileId)}`, {
          method: 'DELETE',
          token: ownerToken
        })
      }
    } catch {}
  }
  for (const item of cleanup.aiObjects) {
    try {
      if (!adminToken && !ownerToken) continue
      const token = adminToken || ownerToken
      const params = new URLSearchParams({
        bucketName: item.bucketName,
        objectName: item.objectName
      })
      const pending = await http(`/api/ai/files?${params}`, { method: 'DELETE', token })
      const confirmationToken = resultData(pending)?.confirmationToken
      if (confirmationToken) {
        params.set('confirmationToken', confirmationToken)
        await http(`/api/ai/files?${params}`, { method: 'DELETE', token })
      }
    } catch {}
  }

  try {
    const docIds = [...cleanup.documentIds]
    if (docIds.length) {
      const ids = docIds.map(sqlString).join(',')
      mysql([
        `DELETE FROM document_annotation WHERE document_id IN (${ids})`,
        `DELETE FROM document_comment WHERE document_id IN (${ids})`,
        `DELETE FROM document_version WHERE document_id IN (${ids})`,
        `DELETE FROM document_access WHERE document_id IN (${ids})`,
        `DELETE FROM document WHERE id IN (${ids})`
      ].join('; '))
    }
    mysql(`DELETE FROM user WHERE username IN (${cleanup.usernames.map(sqlString).join(',')})`)
  } catch (error) {
    console.warn(`[cleanup warning] ${excerpt(error.message)}`)
  }
}

async function run() {
  const portDefinitions = [
    ['INF-001', 'Gateway', 8080],
    ['INF-002', 'User service', 8081],
    ['INF-003', 'File service', 8082],
    ['INF-004', 'AI service', 8083],
    ['INF-005', 'Document service', 8084],
    ['INF-006', 'Web', 5173],
    ['INF-007', 'MySQL', 3306],
    ['INF-008', 'Redis', 6379],
    ['INF-009', 'RabbitMQ', 5672],
    ['INF-010', 'MinIO', 9000]
  ]
  for (const [id, name, port] of portDefinitions) {
    await testCase({
      id, module: '基础设施', name: `${name} 端口监听`,
      target: `127.0.0.1:${port}`, input: 'TCP connect',
      expected: '端口可连接'
    }, async () => {
      const startedAt = performance.now()
      const open = await portOpen(port)
      return { status: open ? 200 : 503, body: { port, open }, elapsedMs: Math.round(performance.now() - startedAt) }
    }, (response) => checkResult(response.body.open, response.body.open ? '' : '端口未监听'))
  }

  await testCase({
    id: 'WEB-001', module: '前端', name: '首页资源加载',
    target: '/', input: 'GET HTML', expected: 'HTTP 200 且标题包含 OmniDoc'
  }, () => http('/', { base: webBase, responseType: 'text' }),
  (response) => checkResult(response.status === 200 && /<title>OmniDoc<\/title>/i.test(response.body), '检查首页 HTML 标题'))

  for (const [id, route] of [['WEB-002', '/login'], ['WEB-003', '/register'], ['WEB-004', '/dashboard'], ['WEB-005', '/agent-workbench'], ['WEB-006', '/aiops'], ['WEB-007', '/ppt-runtime']]) {
    await testCase({
      id, module: '前端', name: `${route} 路由回退`,
      target: route, input: 'GET HTML', expected: 'HTTP 200 且返回 SPA 页面'
    }, () => http(route, { base: webBase, responseType: 'text' }),
    (response) => checkResult(response.status === 200 && response.body.includes('<div id="app">'), 'Vite history fallback'))
  }

  await testCase({
    id: 'SEC-001', module: '安全', name: '受保护接口拒绝无令牌访问',
    target: '/api/ai/models', input: '不携带 Authorization',
    expected: 'HTTP 401'
  }, () => http('/api/ai/models'),
  (response) => checkResult(response.status === 401, `实际 HTTP ${response.status}`))

  const invalidRegister = await testCase({
    id: 'USR-001', module: '用户', name: '注册参数校验',
    target: '/api/users/register', input: '非法邮箱和手机号',
    expected: '业务拒绝，不创建用户'
  }, () => http('/api/users/register', {
    method: 'POST',
    json: { username: `od_invalid_${shortId}`, password: 'x', email: 'bad', phone: '1' }
  }), (response) => checkResult(expectedFailure(response), '非法邮箱/手机号应被拒绝'))

  const ownerRegister = await testCase({
    id: 'USR-002', module: '用户', name: '注册主测试账号',
    target: '/api/users/register', input: `username=${owner.username}`,
    expected: '返回用户 ID，不泄露密码'
  }, () => http('/api/users/register', { method: 'POST', json: owner }),
  (response) => checkResult(successResult(response) && Boolean(resultData(response)?.id) && !('password' in (resultData(response) || {})), '校验用户 ID 与敏感字段'))
  if (ownerRegister.pass) ownerUser = resultData(ownerRegister.response)

  const collabRegister = await testCase({
    id: 'USR-003', module: '用户', name: '注册协作测试账号',
    target: '/api/users/register', input: `username=${collaborator.username}`,
    expected: '返回用户 ID'
  }, () => http('/api/users/register', { method: 'POST', json: collaborator }),
  (response) => checkResult(successResult(response) && Boolean(resultData(response)?.id), '校验协作者用户 ID'))
  if (collabRegister.pass) collaboratorUser = resultData(collabRegister.response)

  await testCase({
    id: 'USR-004', module: '用户', name: '重复用户名注册',
    target: '/api/users/register', input: `重复 username=${owner.username}`,
    expected: '业务拒绝重复注册'
  }, () => http('/api/users/register', {
    method: 'POST',
    json: { ...owner, email: `duplicate_${shortId}@example.com`, phone: `137${shortId.slice(-8).padStart(8, '2')}` }
  }), (response) => checkResult(expectedFailure(response), '重复用户名应返回非成功业务码'))

  await testCase({
    id: 'USR-005', module: '用户', name: '错误密码登录',
    target: '/api/users/login', input: '正确用户名 + 错误密码',
    expected: '业务拒绝登录'
  }, () => http('/api/users/login', {
    method: 'POST',
    json: { username: owner.username, password: 'Wrong@Test123' }
  }), (response) => checkResult(expectedFailure(response), '错误密码应被拒绝'))

  const ownerLogin = await testCase({
    id: 'USR-006', module: '用户', name: '主账号登录与 JWT 签发',
    target: '/api/users/login', input: `username=${owner.username}`,
    expected: '返回 accessToken、refreshToken 和用户信息'
  }, () => http('/api/users/login', {
    method: 'POST',
    json: { username: owner.username, password: owner.password }
  }), (response) => checkResult(successResult(response) &&
    Boolean(resultData(response)?.accessToken) &&
    Boolean(resultData(response)?.refreshToken), '令牌仅记录为已返回，不写入测试报告'))
  if (ownerLogin.pass) {
    ownerToken = resultData(ownerLogin.response).accessToken
    ownerRefreshToken = resultData(ownerLogin.response).refreshToken
  }

  const collabLogin = await testCase({
    id: 'USR-007', module: '用户', name: '协作账号登录',
    target: '/api/users/login', input: `username=${collaborator.username}`,
    expected: '返回有效 JWT'
  }, () => http('/api/users/login', {
    method: 'POST',
    json: { username: collaborator.username, password: collaborator.password }
  }), (response) => checkResult(successResult(response) && Boolean(resultData(response)?.accessToken), '协作者令牌签发'))
  if (collabLogin.pass) collaboratorToken = resultData(collabLogin.response).accessToken

  if (ownerRefreshToken) {
    await testCase({
      id: 'USR-008', module: '用户', name: '刷新访问令牌',
      target: '/api/users/refresh', input: '有效 refreshToken（报告中脱敏）',
      expected: '返回新的 accessToken'
    }, () => http(`/api/users/refresh?refreshToken=${encodeURIComponent(ownerRefreshToken)}`, { method: 'POST' }),
    (response) => checkResult(successResult(response) && typeof resultData(response) === 'string' && resultData(response).length > 20, '刷新令牌成功'))
  } else {
    skipCase({ id: 'USR-008', module: '用户', name: '刷新访问令牌', target: '/api/users/refresh', input: '', expected: '返回新 accessToken' }, '登录前置失败')
  }

  if (collaboratorUser) {
    await testCase({
      id: 'USR-009', module: '用户', name: '按用户名查找协作者',
      target: '/api/users/lookup', input: collaborator.username,
      expected: '返回匹配的用户 ID'
    }, () => http(`/api/users/lookup?username=${encodeURIComponent(collaborator.username)}`, { token: ownerToken }),
    (response) => checkResult(successResult(response) && resultData(response)?.id === collaboratorUser.id, '匹配协作者 ID'))
  }

  if (ownerUser) {
    await testCase({
      id: 'SEC-002', module: '安全', name: '无令牌读取用户详情',
      target: `/api/users/${ownerUser.id}`, input: '不携带 Authorization',
      expected: '应拒绝读取个人信息'
    }, () => http(`/api/users/${ownerUser.id}`),
    (response) => checkResult(response.status === 401 || expectedFailure(response), '若返回 200，说明 /api/users/** 白名单过宽'))
  }

  if (ownerToken) {
    await testCase({
      id: 'USR-010', module: '用户', name: '用户登出接口',
      target: '/api/users/logout', input: '有效 JWT',
      expected: '返回成功；前端负责删除本地令牌'
    }, () => http('/api/users/logout', { method: 'POST', token: ownerToken }))
  }

  if (ownerToken) {
    const upload = await testCase({
      id: 'FIL-001', module: '文件', name: '单文件上传',
      target: '/api/files/upload', input: `${fixtureText.length} 字符 UTF-8 文本`,
      expected: '返回 fileId'
    }, () => http('/api/files/upload', {
      method: 'POST',
      token: ownerToken,
      form: formWithFiles([['file', fixtureText, `omnidoc-${runId}.txt`]])
    }), (response) => checkResult(successResult(response) && Boolean(resultData(response)?.fileId), '校验 fileId'))
    if (upload.pass) {
      primaryFileId = resultData(upload.response).fileId
      cleanup.fileIds.add(primaryFileId)
    }
  }

  if (primaryFileId) {
    await testCase({
      id: 'FIL-002', module: '文件', name: '读取文件元数据',
      target: `/api/files/metadata/${primaryFileId}`, input: 'fileId',
      expected: '文件名、类型、大小和存储位置可读取'
    }, () => http(`/api/files/metadata/${primaryFileId}`, { token: ownerToken }),
    (response) => checkResult(response.status === 200 && response.body?.fileId === primaryFileId && response.body?.fileSize > 0, '元数据为直接 JSON 响应'))

    await testCase({
      id: 'FIL-003', module: '文件', name: '文件列表分页',
      target: '/api/files/list', input: 'page=1,size=100',
      expected: '列表包含刚上传文件'
    }, () => http('/api/files/list?page=1&size=100', { token: ownerToken }),
    (response) => checkResult(response.status === 200 && Array.isArray(response.body) && response.body.some((item) => item.fileId === primaryFileId), '文件列表直接返回数组'))

    await testCase({
      id: 'FIL-004', module: '文件', name: '文件名搜索',
      target: '/api/files/search', input: `keyword=omnidoc-${runId}`,
      expected: '搜索结果包含目标文件'
    }, () => http(`/api/files/search?keyword=${encodeURIComponent(`omnidoc-${runId}`)}&page=1&size=20`, { token: ownerToken }),
    (response) => checkResult(response.status === 200 && Array.isArray(response.body) && response.body.some((item) => item.fileId === primaryFileId), '按文件名关键字搜索'))

    await testCase({
      id: 'FIL-005', module: '文件', name: '目录结构',
      target: '/api/files/directory', input: 'path=/',
      expected: '返回目录结构'
    }, () => http('/api/files/directory?path=%2F', { token: ownerToken }),
    (response) => checkResult(response.status === 200 && response.body !== null, '目录结构可序列化'))

    await testCase({
      id: 'FIL-006', module: '文件', name: '文件名查询',
      target: `/api/files/info/${primaryFileId}/name`, input: 'fileId',
      expected: '返回原始文件名'
    }, () => http(`/api/files/info/${primaryFileId}/name`, { token: ownerToken, responseType: 'text' }),
    (response) => checkResult(response.status === 200 && response.body.includes(`omnidoc-${runId}.txt`), '读取原始文件名'))

    await testCase({
      id: 'FIL-007', module: '文件', name: '文件类型查询',
      target: `/api/files/info/${primaryFileId}/type`, input: 'fileId',
      expected: '返回 text/plain'
    }, () => http(`/api/files/info/${primaryFileId}/type`, { token: ownerToken, responseType: 'text' }),
    (response) => checkResult(response.status === 200 && response.body.toLowerCase().includes('text/plain'), '读取 Content-Type'))

    await testCase({
      id: 'FIL-008', module: '文件', name: '文件预览',
      target: `/api/files/preview/${primaryFileId}`, input: 'fileId',
      expected: '返回原文件字节'
    }, () => http(`/api/files/preview/${primaryFileId}`, { token: ownerToken, responseType: 'buffer' }),
    (response) => checkResult(response.status === 200 && response.body.equals(Buffer.from(fixtureText)), '预览字节与上传内容一致'))

    await testCase({
      id: 'FIL-009', module: '文件', name: '文件下载',
      target: `/api/files/download/${primaryFileId}`, input: 'fileId',
      expected: '返回原文件字节与下载响应头'
    }, () => http(`/api/files/download/${primaryFileId}`, { token: ownerToken, responseType: 'buffer' }),
    (response) => checkResult(response.status === 200 &&
      response.body.equals(Buffer.from(fixtureText)) &&
      Boolean(response.headers['content-disposition']), '下载内容及 Content-Disposition'))

    if (collaboratorToken) {
      await testCase({
        id: 'SEC-003', module: '安全', name: '其他用户读取文件元数据',
        target: `/api/files/metadata/${primaryFileId}`, input: '协作者 JWT + 所有者 fileId',
        expected: '应拒绝越权读取'
      }, () => http(`/api/files/metadata/${primaryFileId}`, { token: collaboratorToken }),
      (response) => checkResult(response.status === 403 || response.status === 404 || expectedFailure(response), '若返回 200，文件服务缺少所有权校验'))

      await testCase({
        id: 'SEC-004', module: '安全', name: '其他用户下载所有者文件',
        target: `/api/files/download/${primaryFileId}`, input: '协作者 JWT + 所有者 fileId',
        expected: '应拒绝越权下载'
      }, () => http(`/api/files/download/${primaryFileId}`, { token: collaboratorToken, responseType: 'buffer' }),
      (response) => checkResult([401, 403, 404].includes(response.status), '若返回 200，文件可被任意已登录用户下载'))
    }

    await testCase({
      id: 'FIL-010', module: '文件', name: '文件重命名',
      target: `/api/files/${primaryFileId}/rename`, input: `newName=renamed-${runId}.txt`,
      expected: '重命名成功且元数据更新'
    }, async () => {
      const update = await http(`/api/files/${primaryFileId}/rename?newName=${encodeURIComponent(`renamed-${runId}.txt`)}`, { method: 'PUT', token: ownerToken })
      const metadata = await http(`/api/files/metadata/${primaryFileId}`, { token: ownerToken })
      return {
        status: update.status,
        body: { update: update.body, metadata: metadata.body },
        elapsedMs: update.elapsedMs + metadata.elapsedMs
      }
    }, (response) => checkResult(response.status === 200 && response.body.metadata?.fileName === `renamed-${runId}.txt`, '重命名后元数据文件名'))

    const copied = await testCase({
      id: 'FIL-011', module: '文件', name: '文件复制',
      target: `/api/files/${primaryFileId}/copy`, input: 'targetPath=/functional-tests',
      expected: '返回新 fileId'
    }, () => http(`/api/files/${primaryFileId}/copy?targetPath=${encodeURIComponent('/functional-tests')}`, { method: 'PUT', token: ownerToken }),
    (response) => checkResult(response.status === 200 && Boolean(response.body?.fileId) && response.body.fileId !== primaryFileId, '复制产生新 fileId'))
    if (copied.pass) cleanup.fileIds.add(copied.response.body.fileId)

    await testCase({
      id: 'FIL-012', module: '文件', name: '文件移动',
      target: `/api/files/${primaryFileId}/move`, input: 'targetPath=/functional-tests/moved',
      expected: '移动成功且文件仍可下载'
    }, async () => {
      const moved = await http(`/api/files/${primaryFileId}/move?targetPath=${encodeURIComponent('/functional-tests/moved')}`, { method: 'PUT', token: ownerToken })
      const downloaded = await http(`/api/files/download/${primaryFileId}`, { token: ownerToken, responseType: 'buffer' })
      return {
        status: moved.status,
        body: { move: moved.body, downloadBytes: downloaded.body.length, downloadStatus: downloaded.status },
        elapsedMs: moved.elapsedMs + downloaded.elapsedMs
      }
    }, (response) => checkResult(response.status === 200 && response.body.downloadStatus === 200 && response.body.downloadBytes > 0, '移动后可按原 fileId 下载'))
  }

  if (ownerToken) {
    const multiple = await testCase({
      id: 'FIL-013', module: '文件', name: '多文件上传',
      target: '/api/files/upload-multiple', input: '2 个小文本文件',
      expected: '返回 2 个 fileId'
    }, () => http('/api/files/upload-multiple', {
      method: 'POST',
      token: ownerToken,
      form: formWithFiles([
        ['files', 'multi-a', `multi-a-${runId}.txt`],
        ['files', 'multi-b', `multi-b-${runId}.txt`]
      ])
    }), (response) => checkResult(successResult(response) && resultData(response)?.count === 2 && resultData(response)?.fileIds?.length === 2, '多文件数量'))
    if (multiple.pass) resultData(multiple.response).fileIds.forEach((id) => cleanup.fileIds.add(id))

    const chunkKey = `chunk-${runId}`
    const partA = 'chunk-A-'
    const partB = 'chunk-B'
    await testCase({
      id: 'FIL-014', module: '文件', name: '分片上传',
      target: '/api/files/upload-chunk', input: '2 个分片',
      expected: '两个分片均返回 HTTP 200'
    }, async () => {
      const first = await http(`/api/files/upload-chunk?fileId=${chunkKey}&chunkIndex=0&totalChunks=2`, {
        method: 'POST', token: ownerToken,
        form: formWithFiles([['chunk', partA, 'part-0.bin', 'application/octet-stream']])
      })
      const second = await http(`/api/files/upload-chunk?fileId=${chunkKey}&chunkIndex=1&totalChunks=2`, {
        method: 'POST', token: ownerToken,
        form: formWithFiles([['chunk', partB, 'part-1.bin', 'application/octet-stream']])
      })
      return {
        status: first.status === 200 && second.status === 200 ? 200 : 500,
        body: { first: first.body, second: second.body },
        elapsedMs: first.elapsedMs + second.elapsedMs
      }
    }, (response) => checkResult(response.status === 200, '分片 0 和 1'))

    const merged = await testCase({
      id: 'FIL-015', module: '文件', name: '合并分片',
      target: '/api/files/merge-chunk', input: `fileId=${chunkKey}, fileName=merged-${runId}.txt`,
      expected: '返回合并后的 fileId，内容顺序正确'
    }, async () => {
      const merge = await http(`/api/files/merge-chunk?fileId=${chunkKey}&fileName=${encodeURIComponent(`merged-${runId}.txt`)}`, { method: 'POST', token: ownerToken })
      const mergedId = merge.body?.fileId
      if (mergedId) cleanup.fileIds.add(mergedId)
      const download = mergedId
        ? await http(`/api/files/download/${mergedId}`, { token: ownerToken, responseType: 'buffer' })
        : { status: 0, body: Buffer.alloc(0), elapsedMs: 0 }
      return {
        status: merge.status,
        body: { merge: merge.body, mergedText: download.body.toString('utf8'), downloadStatus: download.status },
        elapsedMs: merge.elapsedMs + download.elapsedMs
      }
    }, (response) => checkResult(response.status === 200 && response.body.downloadStatus === 200 && response.body.mergedText === partA + partB, '分片合并内容'))
  }

  if (ownerToken) {
    const uploadForDocument = await testCase({
      id: 'DOC-001', module: '文档', name: '上传文档源文件',
      target: '/api/files/upload', input: '文档解析用文本文件',
      expected: '返回 fileId'
    }, () => http('/api/files/upload', {
      method: 'POST',
      token: ownerToken,
      form: formWithFiles([['file', fixtureText, `document-${runId}.txt`]])
    }), (response) => checkResult(successResult(response) && Boolean(resultData(response)?.fileId), '文档源文件上传'))
    if (uploadForDocument.pass) {
      documentFileId = resultData(uploadForDocument.response).fileId
      cleanup.fileIds.add(documentFileId)
    }
  }

  if (documentFileId) {
    const created = await testCase({
      id: 'DOC-002', module: '文档', name: '从文件创建文档',
      target: '/api/documents', input: 'title/fileId/category/tags',
      expected: '解析文件内容并创建版本 1'
    }, () => http('/api/documents', {
      method: 'POST',
      token: ownerToken,
      json: {
        title: `OmniDoc 功能测试 ${runId}`,
        fileId: documentFileId,
        category: 'functional-test',
        tags: ['自动化', '验收']
      }
    }), (response) => checkResult(successResult(response) &&
      Boolean(resultData(response)?.id) &&
      resultData(response)?.version === 1 &&
      String(resultData(response)?.content || '').includes('OmniDoc'), '文档解析、ID 和初始版本'))
    if (created.pass) {
      documentId = resultData(created.response).id
      cleanup.documentIds.add(documentId)
    }
  }

  if (documentId) {
    if (collaboratorToken) {
      await testCase({
        id: 'DOC-003', module: '文档', name: '未授权协作者读取文档',
        target: `/api/documents/${documentId}`, input: '协作者 JWT，授权前',
        expected: '拒绝访问'
      }, () => http(`/api/documents/${documentId}`, { token: collaboratorToken }),
      (response) => checkResult(expectedFailure(response) || [401, 403, 404].includes(response.status), '文档访问控制'))
    }

    await testCase({
      id: 'DOC-004', module: '文档', name: '读取文档详情',
      target: `/api/documents/${documentId}`, input: '所有者 JWT',
      expected: '返回标题、内容、版本'
    }, () => http(`/api/documents/${documentId}`, { token: ownerToken }),
    (response) => checkResult(successResult(response) && resultData(response)?.id === documentId && resultData(response)?.version === 1, '文档详情'))

    await testCase({
      id: 'DOC-005', module: '文档', name: '列出当前用户文档',
      target: `/api/documents/user/${ownerUser?.id}`, input: '所有者 userId',
      expected: '列表包含目标文档'
    }, () => http(`/api/documents/user/${ownerUser.id}`, { token: ownerToken }),
    (response) => checkResult(successResult(response) && resultData(response)?.some((doc) => doc.id === documentId), '所有者文档列表'))

    if (collaboratorUser) {
      await testCase({
        id: 'DOC-006', module: '文档', name: '禁止查询他人文档列表',
        target: `/api/documents/user/${collaboratorUser.id}`, input: '所有者 JWT + 他人 userId',
        expected: '业务拒绝'
      }, () => http(`/api/documents/user/${collaboratorUser.id}`, { token: ownerToken }),
      (response) => checkResult(expectedFailure(response), '禁止横向查询'))
    }

    await testCase({
      id: 'DOC-007', module: '文档', name: '搜索文档',
      target: '/api/documents/search', input: `keyword=${runId}`,
      expected: '搜索结果包含目标文档'
    }, () => http(`/api/documents/search?keyword=${runId}`, { token: ownerToken }),
    (response) => checkResult(successResult(response) && resultData(response)?.some((doc) => doc.id === documentId), '标题关键字搜索'))

    await testCase({
      id: 'DOC-008', module: '文档', name: '读取 MinIO 存储位置',
      target: `/api/documents/${documentId}/storage`, input: 'documentId',
      expected: '返回 bucketName 与 objectName'
    }, () => http(`/api/documents/${documentId}/storage`, { token: ownerToken }),
    (response) => checkResult(successResult(response) && Boolean(resultData(response)?.bucketName) && Boolean(resultData(response)?.objectName), '文档存储位置'))

    await testCase({
      id: 'DOC-009', module: '文档', name: '更新文档并创建历史版本',
      target: `/api/documents/${documentId}`, input: '修改标题、内容、标签和 changeLog',
      expected: '文档版本递增'
    }, () => http(`/api/documents/${documentId}`, {
      method: 'PUT',
      token: ownerToken,
      json: {
        title: `OmniDoc 已更新 ${runId}`,
        content: `${fixtureText}\n第二版内容。`,
        category: 'functional-test',
        tags: ['自动化', '第二版'],
        changeLog: '功能测试更新'
      }
    }), (response) => checkResult(successResult(response) && resultData(response)?.version >= 2 && String(resultData(response)?.content).includes('第二版'), '版本递增和内容更新'))

    const versions = await testCase({
      id: 'DOC-010', module: '文档', name: '文档版本列表',
      target: `/api/documents/${documentId}/versions`, input: 'documentId',
      expected: '至少包含版本 1 和更新版本'
    }, () => http(`/api/documents/${documentId}/versions`, { token: ownerToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)) && resultData(response).length >= 2, '版本数量'))

    if (versions.pass) {
      await testCase({
        id: 'DOC-011', module: '文档', name: '恢复文档历史版本',
        target: `/api/documents/${documentId}/restore/1`, input: 'versionNumber=1',
        expected: '恢复成功并保留版本轨迹'
      }, () => http(`/api/documents/${documentId}/restore/1`, { method: 'POST', token: ownerToken }),
      (response) => checkResult(successResult(response) && String(resultData(response)?.content || '').includes('OmniDoc'), '恢复版本 1'))
    }

    if (collaboratorUser && collaboratorToken) {
      await testCase({
        id: 'DOC-012', module: '协作', name: '授予只读协作者权限',
        target: `/api/documents/${documentId}/collaborators/${collaboratorUser.id}`, input: 'role=viewer',
        expected: '授权成功'
      }, () => http(`/api/documents/${documentId}/collaborators/${collaboratorUser.id}?role=viewer`, { method: 'POST', token: ownerToken }))

      await testCase({
        id: 'DOC-013', module: '协作', name: '协作者读取共享文档',
        target: `/api/documents/${documentId}`, input: 'viewer JWT',
        expected: '读取成功'
      }, () => http(`/api/documents/${documentId}`, { token: collaboratorToken }),
      (response) => checkResult(successResult(response) && resultData(response)?.id === documentId, 'viewer 读取权限'))

      await testCase({
        id: 'DOC-014', module: '协作', name: '只读协作者禁止编辑',
        target: `/api/documents/${documentId}`, input: 'viewer JWT + PUT',
        expected: '业务拒绝写入'
      }, () => http(`/api/documents/${documentId}`, {
        method: 'PUT', token: collaboratorToken,
        json: { content: 'viewer 不应写入', changeLog: '越权测试' }
      }), (response) => checkResult(expectedFailure(response), 'viewer 写权限应被拒绝'))

      await testCase({
        id: 'DOC-015', module: '协作', name: '升级协作者为编辑者',
        target: `/api/documents/${documentId}/collaborators/${collaboratorUser.id}`, input: 'role=editor',
        expected: '授权更新成功'
      }, () => http(`/api/documents/${documentId}/collaborators/${collaboratorUser.id}?role=editor`, { method: 'POST', token: ownerToken }))

      await testCase({
        id: 'DOC-016', module: '协作', name: '编辑者更新共享文档',
        target: `/api/documents/${documentId}`, input: 'editor JWT + PUT',
        expected: '更新成功'
      }, () => http(`/api/documents/${documentId}`, {
        method: 'PUT', token: collaboratorToken,
        json: { content: `${fixtureText}\n协作者编辑成功。`, changeLog: '协作编辑测试' }
      }), (response) => checkResult(successResult(response) && String(resultData(response)?.content).includes('协作者编辑成功'), 'editor 写权限'))

      await testCase({
        id: 'DOC-017', module: '协作', name: '协作者文档列表包含共享文档',
        target: `/api/documents/user/${collaboratorUser.id}`, input: '协作者 JWT',
        expected: '列表包含共享文档'
      }, () => http(`/api/documents/user/${collaboratorUser.id}`, { token: collaboratorToken }),
      (response) => checkResult(successResult(response) && resultData(response)?.some((doc) => doc.id === documentId), '共享文档列表'))
    }

    const comment = await testCase({
      id: 'COM-001', module: '评论', name: '创建文档评论',
      target: `/api/documents/${documentId}/comments`, input: '普通顶级评论',
      expected: '评论 userId 应等于当前登录用户'
    }, () => http(`/api/documents/${documentId}/comments`, {
      method: 'POST', token: ownerToken,
      json: { content: `评论 ${runId}` }
    }), (response) => checkResult(successResult(response) && resultData(response)?.userId === ownerUser?.id, `实际 userId=${resultData(response)?.userId}，当前 userId=${ownerUser?.id}`))
    if (comment.response && successResult(comment.response)) {
      commentId = resultData(comment.response)?.id
    }

    if (commentId) {
      await testCase({
        id: 'COM-002', module: '评论', name: '评论列表',
        target: `/api/documents/${documentId}/comments`, input: 'documentId',
        expected: '列表包含新评论'
      }, () => http(`/api/documents/${documentId}/comments`, { token: ownerToken }),
      (response) => checkResult(successResult(response) && resultData(response)?.some((item) => item.id === commentId), '评论列表'))

      await testCase({
        id: 'COM-003', module: '评论', name: '评论详情',
        target: `/api/documents/${documentId}/comments/${commentId}`, input: 'commentId',
        expected: '返回目标评论'
      }, () => http(`/api/documents/${documentId}/comments/${commentId}`, { token: ownerToken }),
      (response) => checkResult(successResult(response) && resultData(response)?.id === commentId, '评论详情'))

      await testCase({
        id: 'COM-004', module: '评论', name: '评论回复列表',
        target: `/api/documents/${documentId}/comments/${commentId}/replies`, input: '无回复的顶级评论',
        expected: '返回空数组'
      }, () => http(`/api/documents/${documentId}/comments/${commentId}/replies`, { token: ownerToken }),
      (response) => checkResult(successResult(response) && Array.isArray(resultData(response)), '回复数组'))
    }

    const annotation = await testCase({
      id: 'ANN-001', module: '批注', name: '创建文档批注',
      target: `/api/documents/${documentId}/annotations`, input: '第 1 行高亮批注',
      expected: '批注 userId 应等于当前登录用户'
    }, () => http(`/api/documents/${documentId}/annotations`, {
      method: 'POST', token: ownerToken,
      json: {
        lineNumber: 1,
        startOffset: 0,
        endOffset: 7,
        annotationType: 'highlight',
        content: `批注 ${runId}`,
        color: '#fff59d'
      }
    }), (response) => checkResult(successResult(response) && resultData(response)?.userId === ownerUser?.id, `实际 userId=${resultData(response)?.userId}，当前 userId=${ownerUser?.id}`))
    if (annotation.response && successResult(annotation.response)) annotationId = resultData(annotation.response)?.id

    if (annotationId) {
      await testCase({
        id: 'ANN-002', module: '批注', name: '批注列表',
        target: `/api/documents/${documentId}/annotations`, input: 'documentId',
        expected: '列表包含新批注'
      }, () => http(`/api/documents/${documentId}/annotations`, { token: ownerToken }),
      (response) => checkResult(successResult(response) && resultData(response)?.some((item) => item.id === annotationId), '批注列表'))

      await testCase({
        id: 'ANN-003', module: '批注', name: '按行读取批注',
        target: `/api/documents/${documentId}/annotations/line/1`, input: 'lineNumber=1',
        expected: '返回第 1 行批注'
      }, () => http(`/api/documents/${documentId}/annotations/line/1`, { token: ownerToken }),
      (response) => checkResult(successResult(response) && resultData(response)?.some((item) => item.id === annotationId), '行号筛选'))

      await testCase({
        id: 'ANN-004', module: '批注', name: '更新批注',
        target: `/api/documents/${documentId}/annotations/${annotationId}`, input: '修改内容与颜色',
        expected: '更新成功且详情反映新内容'
      }, async () => {
        const updated = await http(`/api/documents/${documentId}/annotations/${annotationId}`, {
          method: 'PUT', token: ownerToken,
          json: {
            lineNumber: 1, startOffset: 0, endOffset: 7,
            annotationType: 'note', content: `已更新批注 ${runId}`, color: '#90caf9'
          }
        })
        const detail = await http(`/api/documents/${documentId}/annotations/${annotationId}`, { token: ownerToken })
        return {
          status: updated.status,
          body: { update: updated.body, detail: detail.body },
          elapsedMs: updated.elapsedMs + detail.elapsedMs
        }
      }, (response) => checkResult(response.status === 200 && response.body.update?.code === 200 && response.body.detail?.data?.content === `已更新批注 ${runId}`, '更新后详情'))
    }
  }

  if (ownerToken) {
    await testCase({
      id: 'AI-001', module: 'AI', name: '模型列表',
      target: '/api/ai/models', input: '有效 JWT',
      expected: `列表包含 ${model}`
    }, () => http('/api/ai/models', { token: ownerToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)) && resultData(response).some((item) => item.code === model && item.available === true), '百炼模型已注册且可用'))

    await testCase({
      id: 'AI-002', module: 'AI', name: '本地文本分析',
      target: '/api/ai/analyze', input: '短中文文本',
      expected: '返回字数、句子等分析结果'
    }, () => http('/api/ai/analyze', { method: 'POST', token: ownerToken, json: { content: fixtureText } }),
    (response) => checkResult(successResult(response) && resultData(response) && Object.keys(resultData(response)).length > 0, '分析结果非空'))

    await testCase({
      id: 'AI-003', module: 'AI', name: '百炼同步摘要',
      target: '/api/ai/summarize', input: `model=${model}, maxLength=40`,
      expected: '调用百炼并返回非空摘要'
    }, () => http(`/api/ai/summarize?model=${encodeURIComponent(model)}`, {
      method: 'POST', token: ownerToken,
      json: { content: fixtureText, maxLength: 40 }
    }), (response) => checkResult(successResult(response) && String(resultData(response)?.summary || '').trim().length > 0, '同步摘要内容'))

    await testCase({
      id: 'AI-004', module: 'AI', name: '百炼同步关键词',
      target: '/api/ai/keywords', input: `model=${model}, count=3`,
      expected: '调用百炼并返回关键词'
    }, () => http(`/api/ai/keywords?model=${encodeURIComponent(model)}`, {
      method: 'POST', token: ownerToken,
      json: { content: fixtureText, count: 3 }
    }), (response) => checkResult(successResult(response) && Array.isArray(resultData(response)?.keywords) && resultData(response).keywords.length > 0, '关键词数组'))

    await testCase({
      id: 'AI-005', module: 'AI', name: '无效模型代码',
      target: '/api/ai/summarize', input: 'model=not-a-model',
      expected: '业务拒绝，不静默回退'
    }, () => http('/api/ai/summarize?model=not-a-model', {
      method: 'POST', token: ownerToken,
      json: { content: '测试', maxLength: 10 }
    }), (response) => checkResult(expectedFailure(response), '无效模型应明确报错'))

    const asyncAnalyzeSubmit = await testCase({
      id: 'ASY-001', module: '异步 AI', name: '提交异步文本分析',
      target: '/api/ai/async/analyze', input: '短文本',
      expected: '立即返回 jobId'
    }, () => http('/api/ai/async/analyze', { method: 'POST', token: ownerToken, json: { content: fixtureText } }),
    (response) => checkResult(successResult(response) && Boolean(resultData(response)?.jobId), 'RabbitMQ 任务 ID'))
    if (asyncAnalyzeSubmit.pass) {
      const jobId = resultData(asyncAnalyzeSubmit.response).jobId
      await testCase({
        id: 'ASY-002', module: '异步 AI', name: '异步文本分析完成',
        target: `/api/ai/async/jobs/${jobId}`, input: '轮询 jobId',
        expected: '最终状态 success 且结果非空'
      }, () => pollAsyncJob(jobId, ownerToken),
      (response) => checkResult(successResult(response) && String(resultData(response)?.status).toLowerCase() === 'success' && resultData(response)?.result, '异步任务落库/缓存与消费'))
    }

    const asyncSummarySubmit = await testCase({
      id: 'ASY-003', module: '异步 AI', name: '提交异步百炼摘要',
      target: '/api/ai/async/summarize', input: `model=${model}`,
      expected: '立即返回 jobId'
    }, () => http(`/api/ai/async/summarize?model=${encodeURIComponent(model)}`, {
      method: 'POST', token: ownerToken,
      json: { content: fixtureText, maxLength: 30 }
    }), (response) => checkResult(successResult(response) && Boolean(resultData(response)?.jobId), '异步摘要 jobId'))
    if (asyncSummarySubmit.pass) {
      const jobId = resultData(asyncSummarySubmit.response).jobId
      await testCase({
        id: 'ASY-004', module: '异步 AI', name: '异步百炼摘要完成',
        target: `/api/ai/async/jobs/${jobId}`, input: '轮询 jobId',
        expected: '最终状态 success'
      }, () => pollAsyncJob(jobId, ownerToken),
      (response) => checkResult(successResult(response) && String(resultData(response)?.status).toLowerCase() === 'success' && resultData(response)?.result, 'RabbitMQ 消费与百炼结果'))
    }

    const asyncKeywordsSubmit = await testCase({
      id: 'ASY-005', module: '异步 AI', name: '提交异步百炼关键词',
      target: '/api/ai/async/keywords', input: `model=${model}, count=3`,
      expected: '立即返回 jobId'
    }, () => http(`/api/ai/async/keywords?model=${encodeURIComponent(model)}`, {
      method: 'POST', token: ownerToken,
      json: { content: fixtureText, count: 3 }
    }), (response) => checkResult(successResult(response) && Boolean(resultData(response)?.jobId), '异步关键词 jobId'))
    if (asyncKeywordsSubmit.pass) {
      const jobId = resultData(asyncKeywordsSubmit.response).jobId
      await testCase({
        id: 'ASY-006', module: '异步 AI', name: '异步百炼关键词完成',
        target: `/api/ai/async/jobs/${jobId}`, input: '轮询 jobId',
        expected: '最终状态 success'
      }, () => pollAsyncJob(jobId, ownerToken),
      (response) => checkResult(successResult(response) && String(resultData(response)?.status).toLowerCase() === 'success' && resultData(response)?.result, '异步关键词结果'))
    }

    const asyncChatSubmit = await testCase({
      id: 'ASY-007', module: '异步 AI', name: '提交异步通用对话',
      target: '/api/ai/async/chat', input: '只要求回复 OK',
      expected: '立即返回 jobId'
    }, () => http(`/api/ai/async/chat?model=${encodeURIComponent(model)}`, {
      method: 'POST', token: ownerToken,
      json: { prompt: '只回复 OK' }
    }), (response) => checkResult(successResult(response) && Boolean(resultData(response)?.jobId), '异步聊天 jobId'))
    if (asyncChatSubmit.pass) {
      const jobId = resultData(asyncChatSubmit.response).jobId
      await testCase({
        id: 'ASY-008', module: '异步 AI', name: '异步通用对话完成',
        target: `/api/ai/async/jobs/${jobId}`, input: '轮询 jobId',
        expected: '最终状态 success 且有回复'
      }, () => pollAsyncJob(jobId, ownerToken),
      (response) => checkResult(successResult(response) && String(resultData(response)?.status).toLowerCase() === 'success' && resultData(response)?.result, '异步聊天结果'))
    }
  }

  if (ownerToken) {
    const aiUpload = await testCase({
      id: 'AIF-001', module: 'AI 文件', name: '用户隔离桶上传',
      target: '/api/ai/upload', input: `objectName=functional-tests/${runId}.txt`,
      expected: '上传到当前用户专属桶并返回对象名'
    }, () => http('/api/ai/upload', {
      method: 'POST', token: ownerToken,
      form: formWithFiles([['file', fixtureText, `ai-file-${runId}.txt`]], {
        objectName: `functional-tests/${runId}.txt`
      })
    }), (response) => checkResult(successResult(response) && Boolean(resultData(response)?.bucketName) && resultData(response)?.objectName === `functional-tests/${runId}.txt`, '用户桶和对象名'))
    if (aiUpload.pass) {
      aiFile = resultData(aiUpload.response)
      cleanup.aiObjects.push({ bucketName: aiFile.bucketName, objectName: aiFile.objectName })
    }
  }

  if (aiFile) {
    const query = new URLSearchParams({ bucketName: aiFile.bucketName, objectName: aiFile.objectName })
    await testCase({
      id: 'AIF-002', module: 'AI 文件', name: '获取文件预签名 URL',
      target: '/api/ai/download/url', input: '当前用户桶对象',
      expected: '返回可访问 URL'
    }, () => http(`/api/ai/download/url?${query}`, { token: ownerToken }),
    (response) => checkResult(successResult(response) && Boolean(resultData(response)?.fileUrl), '预签名 URL'))

    await testCase({
      id: 'AIF-003', module: 'AI 文件', name: '直接下载用户桶文件',
      target: '/api/ai/download', input: '当前用户桶对象',
      expected: '下载字节与上传内容一致'
    }, () => http(`/api/ai/download?${query}`, { token: ownerToken, responseType: 'buffer' }),
    (response) => checkResult(response.status === 200 && response.body.equals(Buffer.from(fixtureText)), 'AI 文件下载内容'))

    await testCase({
      id: 'AIF-004', module: 'AI 文件', name: '文件版本列表',
      target: '/api/ai/files/versions', input: '当前用户桶对象',
      expected: '至少包含上传版本'
    }, () => http(`/api/ai/files/versions?${query}`, { token: ownerToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)?.versions) && resultData(response).versions.length >= 1, '版本记录'))

    if (collaboratorToken) {
      await testCase({
        id: 'SEC-005', module: '安全', name: '用户桶跨用户下载隔离',
        target: '/api/ai/download', input: '协作者 JWT + 所有者桶对象',
        expected: '拒绝访问'
      }, () => http(`/api/ai/download?${query}`, { token: collaboratorToken, responseType: 'buffer' }),
      (response) => checkResult([401, 403, 500].includes(response.status) || response.body.length === 0, '用户桶权限检查'))
    }
  }

  if (ownerToken) {
    const skillUpload = await testCase({
      id: 'SKL-001', module: '技能', name: '文件上传技能',
      target: '/api/skills/file/upload', input: `objectName=functional-tests/skill-${runId}.txt`,
      expected: '技能执行成功'
    }, () => http('/api/skills/file/upload', {
      method: 'POST', token: ownerToken,
      form: formWithFiles([['file', 'skill-file', `skill-${runId}.txt`]], {
        objectName: `functional-tests/skill-${runId}.txt`
      })
    }), (response) => checkResult(successResult(response) && resultData(response), '文件上传 Skill'))
    if (skillUpload.pass && ownerUser) {
      skillFile = {
        bucketName: `user-${ownerUser.id}`,
        objectName: `functional-tests/skill-${runId}.txt`
      }
      cleanup.aiObjects.push(skillFile)
    }

    await testCase({
      id: 'SKL-002', module: '技能', name: '读取技能描述',
      target: '/api/skills/description/File%20Upload%20Skill', input: 'skillName',
      expected: '返回非空技能说明'
    }, () => http('/api/skills/description/File%20Upload%20Skill', { token: ownerToken }),
    (response) => checkResult(successResult(response) && String(resultData(response) || '').length > 0, '技能描述'))
  }

  if (skillFile) {
    const query = new URLSearchParams(skillFile)
    await testCase({
      id: 'SKL-003', module: '技能', name: '文件下载技能',
      target: '/api/skills/file/download', input: '用户桶对象',
      expected: '返回上传内容'
    }, () => http(`/api/skills/file/download?${query}`, { token: ownerToken, responseType: 'buffer' }),
    (response) => checkResult(response.status === 200 && response.body.toString('utf8') === 'skill-file', '文件下载 Skill'))
  }

  if (ownerToken) {
    await testCase({
      id: 'RAG-001', module: 'RAG', name: '分段策略列表',
      target: '/api/ai/rag/segment/strategies', input: '有效 JWT',
      expected: '包含 AUTO、FIXED_LENGTH 等策略'
    }, () => http('/api/ai/rag/segment/strategies', { token: ownerToken }),
    (response) => checkResult(successResult(response) && resultData(response)?.AUTO !== undefined, '分段策略'))

    await testCase({
      id: 'RAG-002', module: 'RAG', name: '重排策略列表',
      target: '/api/ai/rag/rerank/strategies', input: '有效 JWT',
      expected: '返回支持的重排策略'
    }, () => http('/api/ai/rag/rerank/strategies', { token: ownerToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)) && resultData(response).length > 0, '重排策略'))

    await testCase({
      id: 'RAG-003', module: 'RAG', name: '无效分段策略',
      target: '/api/ai/rag/index/segment', input: 'strategy=INVALID',
      expected: '业务拒绝'
    }, () => http(`/api/ai/rag/index/segment?documentId=invalid-${runId}&strategy=INVALID`, {
      method: 'POST', token: ownerToken, text: '不会被索引'
    }), (response) => checkResult(expectedFailure(response), '无效策略校验'))

    ragDocumentId = `rag-${runId}`
    const indexed = await testCase({
      id: 'RAG-004', module: 'RAG', name: '文档分段与向量索引',
      target: '/api/ai/rag/index/segment', input: 'strategy=FIXED_LENGTH，短测试文档',
      expected: '调用百炼 Embedding 并建立索引'
    }, () => http(`/api/ai/rag/index/segment?documentId=${ragDocumentId}&strategy=FIXED_LENGTH`, {
      method: 'POST', token: ownerToken, text: fixtureText
    }), (response) => checkResult(successResult(response), '向量索引成功'))
    if (indexed.pass) cleanup.ragDocumentIds.add(ragDocumentId)
  }

  if (ragDocumentId && cleanup.ragDocumentIds.has(ragDocumentId)) {
    await testCase({
      id: 'RAG-005', module: 'RAG', name: '读取知识库原文',
      target: `/api/ai/rag/document/${ragDocumentId}`, input: 'documentId',
      expected: '返回原文'
    }, () => http(`/api/ai/rag/document/${ragDocumentId}`, { token: ownerToken }),
    (response) => checkResult(successResult(response) && String(resultData(response)).includes(runId), '知识库原文'))

    await testCase({
      id: 'RAG-006', module: 'RAG', name: '知识库文档元数据',
      target: `/api/ai/rag/document/${ragDocumentId}/metadata`, input: 'documentId',
      expected: 'userId 与当前用户一致'
    }, () => http(`/api/ai/rag/document/${ragDocumentId}/metadata`, { token: ownerToken }),
    (response) => checkResult(successResult(response) && String(resultData(response)?.userId) === String(ownerUser?.id), '索引所有权元数据'))

    await testCase({
      id: 'RAG-007', module: 'RAG', name: '文档分段列表',
      target: `/api/ai/rag/document/${ragDocumentId}/segments`, input: 'documentId',
      expected: '至少返回 1 个分段'
    }, () => http(`/api/ai/rag/document/${ragDocumentId}/segments`, { token: ownerToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)) && resultData(response).length >= 1, '分段结果'))

    await testCase({
      id: 'RAG-008', module: 'RAG', name: '基础向量检索',
      target: '/api/ai/rag/search', input: 'query=海棠, topK=3',
      expected: '命中测试文档'
    }, () => http('/api/ai/rag/search?query=%E6%B5%B7%E6%A3%A0&topK=3', { token: ownerToken }),
    (response) => checkResult(successResult(response) && resultData(response)?.some((item) => item.documentId === ragDocumentId || item.metadata?.documentId === ragDocumentId), '基础向量检索命中'))

    await testCase({
      id: 'RAG-009', module: 'RAG', name: '混合检索',
      target: '/api/ai/rag/search/hybrid', input: 'query=知识库, topK=3',
      expected: '命中测试文档'
    }, () => http('/api/ai/rag/search/hybrid?query=%E7%9F%A5%E8%AF%86%E5%BA%93&topK=3', { token: ownerToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)) && resultData(response).length > 0, '混合检索结果'))

    await testCase({
      id: 'RAG-010', module: 'RAG', name: '混合检索与重排',
      target: '/api/ai/rag/search/hybrid/rerank', input: 'strategy=BM25_FUSION',
      expected: '返回重排结果'
    }, () => http('/api/ai/rag/search/hybrid/rerank?query=%E6%B5%B7%E6%A3%A0&topK=3&strategy=BM25_FUSION', { token: ownerToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)) && resultData(response).length > 0, 'BM25 融合重排'))

    await testCase({
      id: 'RAG-011', module: 'RAG', name: '知识库统计',
      target: '/api/ai/rag/statistics', input: 'default knowledgeBase',
      expected: '文档数和分段数大于 0'
    }, () => http('/api/ai/rag/statistics', { token: ownerToken }),
    (response) => checkResult(successResult(response) && Number(resultData(response)?.documentCount || 0) >= 1, '知识库统计'))

    if (collaboratorToken) {
      await testCase({
        id: 'SEC-006', module: '安全', name: 'RAG 文档跨用户隔离',
        target: `/api/ai/rag/document/${ragDocumentId}`, input: '协作者 JWT + 所有者索引 ID',
        expected: '拒绝访问'
      }, () => http(`/api/ai/rag/document/${ragDocumentId}`, { token: collaboratorToken }),
      (response) => checkResult(expectedFailure(response) || [401, 403, 404].includes(response.status), 'RAG 所有权校验'))
    }

    await testCase({
      id: 'RAG-012', module: 'RAG', name: '知识库问答',
      target: '/api/ai/rag/query', input: '问题：验收关键词是什么？ strategy=BM25_FUSION',
      expected: '基于索引内容调用百炼生成答案'
    }, () => http('/api/ai/rag/query?strategy=BM25_FUSION', {
      method: 'POST', token: ownerToken, text: '验收关键词是什么？'
    }), (response) => checkResult(successResult(response) && String(resultData(response)?.answer || '').trim().length > 0, 'RAG 问答答案'))
  }

  if (ownerToken) {
    await testCase({
      id: 'AGT-001', module: 'Agent', name: 'Agent 工具列表',
      target: '/api/ai/agent/tools', input: '有效 JWT',
      expected: '返回工具 Schema 列表'
    }, () => http('/api/ai/agent/tools', { token: ownerToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)) && resultData(response).length > 0, 'Agent 工具'))

    await testCase({
      id: 'AGT-002', module: 'Agent', name: 'Agent 工作台总览',
      target: '/api/ai/agent/workbench/overview', input: '有效 JWT',
      expected: '包含 tools/conversations/tasks/knowledgeJobs/user'
    }, () => http('/api/ai/agent/workbench/overview', { token: ownerToken }),
    (response) => checkResult(successResult(response) && ['tools', 'conversations', 'tasks', 'knowledgeJobs', 'user'].every((key) => key in (resultData(response) || {})), '工作台聚合字段'))

    const dryRun = await testCase({
      id: 'AGT-003', module: 'Agent', name: 'Agent 安全规划（dryRun）',
      target: '/api/ai/agent/execute', input: '只规划回答 OK，不执行工具',
      expected: '调用百炼完成规划，不产生写操作'
    }, () => http('/api/ai/agent/execute', {
      method: 'POST', token: ownerToken,
      json: {
        task: '只规划如何回答“OK”，不要调用任何工具。',
        model,
        ragEnabled: false,
        dryRun: true,
        maxIterations: 1,
        maxToolCalls: 0
      }
    }), (response) => checkResult(successResult(response) && resultData(response), 'dryRun 规划结果'))
    const dryTrace = resultData(dryRun.response)?.traceId
    if (dryTrace) cleanup.agentTraceIds.add(dryTrace)

    const asyncAgentSubmit = await testCase({
      id: 'AGT-004', module: 'Agent', name: '提交异步 Agent 规划',
      target: '/api/ai/async/agent/execute', input: 'dryRun=true',
      expected: '立即返回 jobId'
    }, () => http('/api/ai/async/agent/execute', {
      method: 'POST', token: ownerToken,
      json: {
        task: '只规划回答“异步 OK”，不要调用工具。',
        model,
        ragEnabled: false,
        dryRun: true,
        maxIterations: 1,
        maxToolCalls: 0
      }
    }), (response) => checkResult(successResult(response) && Boolean(resultData(response)?.jobId), '异步 Agent jobId'))
    if (asyncAgentSubmit.pass) {
      const jobId = resultData(asyncAgentSubmit.response).jobId
      await testCase({
        id: 'AGT-005', module: 'Agent', name: '异步 Agent 规划完成',
        target: `/api/ai/async/jobs/${jobId}`, input: '轮询 jobId',
        expected: '最终状态 success'
      }, () => pollAsyncJob(jobId, ownerToken),
      (response) => {
        const traceId = resultData(response)?.result?.traceId
        if (traceId) cleanup.agentTraceIds.add(traceId)
        return checkResult(successResult(response) && String(resultData(response)?.status).toLowerCase() === 'success', '异步 Agent 结果')
      })
    }

    const startConversation = await testCase({
      id: 'AGT-006', module: 'Agent', name: '开始 Agent 对话',
      target: '/api/ai/agent/chat/start', input: '当前用户',
      expected: '返回 conversationId'
    }, () => http('/api/ai/agent/chat/start', { method: 'POST', token: ownerToken }),
    (response) => checkResult(successResult(response) && typeof resultData(response) === 'string', 'conversationId'))
    if (startConversation.pass) {
      conversationId = resultData(startConversation.response)
      cleanup.conversationIds.add(conversationId)
    }
  }

  if (conversationId) {
    const chat = await testCase({
      id: 'AGT-007', module: 'Agent', name: 'Agent 对话消息',
      target: '/api/ai/agent/chat', input: '只回复“对话测试成功”',
      expected: '调用百炼并返回结果'
    }, () => http(`/api/ai/agent/chat?conversationId=${encodeURIComponent(conversationId)}`, {
      method: 'POST', token: ownerToken, text: '只回复“对话测试成功”，不要调用工具。'
    }), (response) => checkResult(successResult(response) && resultData(response), 'Agent 对话结果'))
    const chatTrace = resultData(chat.response)?.traceId
    if (chatTrace) cleanup.agentTraceIds.add(chatTrace)

    await testCase({
      id: 'AGT-008', module: 'Agent', name: 'Agent 对话历史',
      target: `/api/ai/agent/chat/${conversationId}/history`, input: 'conversationId',
      expected: '历史包含用户输入'
    }, () => http(`/api/ai/agent/chat/${conversationId}/history`, { token: ownerToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)) && resultData(response).length > 0, '对话历史'))

    await testCase({
      id: 'AGT-009', module: 'Agent', name: '结束 Agent 对话',
      target: `/api/ai/agent/chat/${conversationId}/end`, input: 'conversationId',
      expected: '返回 true'
    }, () => http(`/api/ai/agent/chat/${conversationId}/end`, { method: 'POST', token: ownerToken }),
    (response) => checkResult(successResult(response) && resultData(response) === true, '对话已结束'))
    cleanup.conversationIds.delete(conversationId)
  }

  if (ownerToken) {
    await testCase({
      id: 'AGT-010', module: 'Agent', name: 'Agent 任务列表',
      target: '/api/ai/agent/tasks', input: '当前用户',
      expected: '返回任务数组'
    }, () => http('/api/ai/agent/tasks', { token: ownerToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)), '任务历史'))

    await testCase({
      id: 'AGT-011', module: 'Agent', name: '取消无效审批令牌',
      target: '/api/ai/agent/approvals/not-a-token/cancel', input: '无效 token',
      expected: '安全返回 false，不执行任何操作'
    }, () => http('/api/ai/agent/approvals/not-a-token/cancel', { method: 'POST', token: ownerToken }),
    (response) => checkResult(successResult(response) && resultData(response) === false, '无效审批令牌'))

    const knowledgeDocId = `agent-kb-${runId}`
    const indexed = await testCase({
      id: 'AGT-012', module: 'Agent 知识', name: '同步知识索引 Agent',
      target: '/api/ai/agent/knowledge/index', input: '短文档',
      expected: '返回索引质量和状态'
    }, () => http(`/api/ai/agent/knowledge/index?documentId=${knowledgeDocId}`, {
      method: 'POST', token: ownerToken, text: `Agent 知识索引测试 ${runId}，关键词：银杏。`
    }), (response) => checkResult(successResult(response) && resultData(response), '同步知识索引'))
    if (indexed.pass) cleanup.ragDocumentIds.add(knowledgeDocId)

    await testCase({
      id: 'AGT-013', module: 'Agent 知识', name: 'Agent 知识检索',
      target: '/api/ai/agent/knowledge/search', input: 'query=银杏',
      expected: '返回检索数组'
    }, () => http('/api/ai/agent/knowledge/search?query=%E9%93%B6%E6%9D%8F&topK=3', { token: ownerToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)), 'Agent 知识检索'))

    const asyncKnowledgeId = `agent-kb-async-${runId}`
    const asyncIndex = await testCase({
      id: 'AGT-014', module: 'Agent 知识', name: '提交异步知识索引',
      target: '/api/ai/agent/knowledge/index/async', input: '短文档',
      expected: '立即返回知识任务 ID'
    }, () => http(`/api/ai/agent/knowledge/index/async?documentId=${asyncKnowledgeId}`, {
      method: 'POST', token: ownerToken, text: `异步知识索引 ${runId}，关键词：梧桐。`
    }), (response) => checkResult(successResult(response) && Boolean(resultData(response)?.jobId), '异步知识任务 ID'))
    if (asyncIndex.pass) {
      asyncKnowledgeJobId = resultData(asyncIndex.response).jobId
      cleanup.knowledgeJobIds.add(asyncKnowledgeJobId)
      cleanup.ragDocumentIds.add(asyncKnowledgeId)
      await testCase({
        id: 'AGT-015', module: 'Agent 知识', name: '异步知识索引完成',
        target: `/api/ai/agent/knowledge/jobs/${asyncKnowledgeJobId}`, input: '轮询 jobId',
        expected: '最终完成或成功'
      }, () => pollKnowledgeJob(asyncKnowledgeJobId, ownerToken),
      (response) => checkResult(successResult(response) && ['completed', 'success'].includes(String(resultData(response)?.status).toLowerCase()), '异步知识索引状态'))
    }

    await testCase({
      id: 'AGT-016', module: 'Agent 知识', name: '知识索引任务列表',
      target: '/api/ai/agent/knowledge/jobs', input: '当前用户',
      expected: '返回任务数组'
    }, () => http('/api/ai/agent/knowledge/jobs', { token: ownerToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)), '知识任务列表'))

    skipCase({
      id: 'AGT-017', module: 'Agent', name: '确认真实危险操作',
      target: '/api/ai/agent/approvals/confirm', input: '真实审批 token',
      expected: '确认后执行危险操作'
    }, '为避免删除或覆盖用户数据，本轮只验证无效审批令牌的安全处理，不生成真实危险操作')
    skipCase({
      id: 'AGT-018', module: 'Agent', name: '失败步骤重试与 action_required 续接',
      target: '/api/ai/agent/tasks/{traceId}/steps/{stepId}/retry', input: '失败步骤',
      expected: '按状态机继续执行'
    }, '当前安全测试任务未产生失败步骤或 action_required 状态')
  }

  if (ownerToken) {
    await testCase({
      id: 'OPS-001', module: 'AIOps', name: '记录计数指标',
      target: '/api/ai/aiops/metrics/counter', input: `name=functional.${runId},delta=2`,
      expected: '记录成功'
    }, () => http(`/api/ai/aiops/metrics/counter?name=functional.${runId}&delta=2`, { method: 'POST', token: ownerToken }))

    await testCase({
      id: 'OPS-002', module: 'AIOps', name: '记录耗时指标',
      target: '/api/ai/aiops/metrics/timer', input: `name=functional.timer,duration=123`,
      expected: '记录成功'
    }, () => http('/api/ai/aiops/metrics/timer?name=functional.timer&duration=123', { method: 'POST', token: ownerToken }))

    await testCase({
      id: 'OPS-003', module: 'AIOps', name: '读取监控指标',
      target: '/api/ai/aiops/monitor', input: '有效 JWT',
      expected: '返回监控对象'
    }, () => http('/api/ai/aiops/monitor', { token: ownerToken }),
    (response) => checkResult(successResult(response) && resultData(response) && Object.keys(resultData(response)).length > 0, '监控指标'))

    await testCase({
      id: 'OPS-004', module: 'AIOps', name: 'AIOps 健康检查',
      target: '/api/ai/aiops/health', input: '有效 JWT',
      expected: '返回健康状态对象'
    }, () => http('/api/ai/aiops/health', { token: ownerToken }),
    (response) => checkResult(successResult(response) && resultData(response)?.status, '业务健康检查（非 Actuator）'))

    await testCase({
      id: 'OPS-005', module: 'AIOps', name: '普通用户禁止故障检测',
      target: '/api/ai/aiops/detect', input: 'USER JWT',
      expected: '拒绝管理员操作'
    }, () => http('/api/ai/aiops/detect', { token: ownerToken }),
    (response) => checkResult(expectedFailure(response) || [401, 403].includes(response.status), '管理员权限控制'))
  }

  if (ownerUser) {
    const adminFixture = await testCase({
      id: 'SET-001', module: '测试前置', name: '临时账号提升为 ADMIN',
      target: 'MySQL user.role', input: `仅测试账号 ${owner.username}`,
      expected: '角色更新为 ADMIN，测试结束删除账号'
    }, async () => {
      const startedAt = performance.now()
      mysql(`UPDATE user SET role='ADMIN' WHERE username=${sqlString(owner.username)}; SELECT role FROM user WHERE username=${sqlString(owner.username)}`)
      return { status: 200, body: { role: 'ADMIN' }, elapsedMs: Math.round(performance.now() - startedAt) }
    }, (response) => checkResult(response.body.role === 'ADMIN', '仅修改隔离测试账号'))

    if (adminFixture.pass) {
      const adminLogin = await testCase({
        id: 'USR-011', module: '用户', name: '管理员角色重新登录',
        target: '/api/users/login', input: '临时 ADMIN 账号',
        expected: 'JWT 携带管理员角色'
      }, () => http('/api/users/login', {
        method: 'POST',
        json: { username: owner.username, password: owner.password }
      }), (response) => checkResult(successResult(response) && Boolean(resultData(response)?.accessToken) && String(resultData(response)?.user?.role).toUpperCase() === 'ADMIN', '管理员 JWT'))
      if (adminLogin.pass) adminToken = resultData(adminLogin.response).accessToken
    }
  }

  if (adminToken) {
    const detected = await testCase({
      id: 'OPS-006', module: 'AIOps', name: '管理员执行故障检测',
      target: '/api/ai/aiops/detect', input: 'ADMIN JWT',
      expected: '返回故障数组'
    }, () => http('/api/ai/aiops/detect', { token: adminToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)), '管理员故障检测'))

    await testCase({
      id: 'OPS-007', module: 'AIOps', name: '管理员读取故障列表',
      target: '/api/ai/aiops/faults', input: 'ADMIN JWT',
      expected: '返回故障记录数组'
    }, () => http('/api/ai/aiops/faults', { token: adminToken }),
    (response) => checkResult(successResult(response) && Array.isArray(resultData(response)), '故障列表'))

    const faultId = resultData(detected.response)?.[0]?.id
    if (faultId) {
      await testCase({
        id: 'OPS-008', module: 'AIOps', name: '处理检测到的故障',
        target: `/api/ai/aiops/faults/${faultId}/resolve`, input: 'ADMIN JWT + faultId',
        expected: '返回 status=success'
      }, () => http(`/api/ai/aiops/faults/${faultId}/resolve`, { method: 'POST', token: adminToken }),
      (response) => checkResult(successResult(response) && resultData(response)?.status === 'success', '故障处理'))
    } else {
      await testCase({
        id: 'OPS-008', module: 'AIOps', name: '不存在故障的处理结果',
        target: '/api/ai/aiops/faults/not-found/resolve', input: 'ADMIN JWT + 不存在 faultId',
        expected: '业务数据返回 status=error，不误报处理成功'
      }, () => http('/api/ai/aiops/faults/not-found/resolve', { method: 'POST', token: adminToken }),
      (response) => checkResult(successResult(response) && resultData(response)?.status === 'error', '无活动故障时的安全分支'))
    }

    await testCase({
      id: 'OPS-009', module: 'AIOps', name: '管理员重置测试指标',
      target: '/api/ai/aiops/metrics/reset', input: 'ADMIN JWT',
      expected: '重置成功'
    }, () => http('/api/ai/aiops/metrics/reset', { method: 'POST', token: adminToken }))
  }

  if (ownerToken) {
    const pptPayload = {
      outline: '1. OmniDoc 功能测试\n2. 测试通过',
      theme: 'modern',
      title: `OmniDoc测试${shortId}`,
      model
    }
    await testCase({
      id: 'PPT-001', module: 'HTML PPT', name: '生成 HTML PPT JSON',
      target: '/api/skills/html-ppt/generate', input: '2 页极简大纲 + 百炼模型',
      expected: '返回 htmlContent、fileName 和 success=true'
    }, () => http('/api/skills/html-ppt/generate', { method: 'POST', token: ownerToken, json: pptPayload }),
    (response) => checkResult(successResult(response) && resultData(response)?.success === true && String(resultData(response)?.htmlContent || '').length > 300, 'HTML PPT 内容'))

    await testCase({
      id: 'PPT-002', module: 'HTML PPT', name: '浏览器预览 HTML PPT',
      target: '/api/skills/html-ppt/view', input: '2 页极简大纲 + 百炼模型',
      expected: '返回 text/html'
    }, () => http('/api/skills/html-ppt/view', { method: 'POST', token: ownerToken, json: pptPayload, responseType: 'text' }),
    (response) => checkResult(response.status === 200 && (response.headers['content-type'] || '').includes('text/html') && response.body.length > 300, 'HTML 预览响应'))

    await testCase({
      id: 'PPT-003', module: 'HTML PPT', name: '下载 HTML PPT',
      target: '/api/skills/html-ppt/download', input: '2 页极简大纲 + 百炼模型',
      expected: '返回 HTML 文件与下载响应头'
    }, () => http('/api/skills/html-ppt/download', { method: 'POST', token: ownerToken, json: pptPayload, responseType: 'buffer' }),
    (response) => checkResult(response.status === 200 && response.body.length > 300 && Boolean(response.headers['content-disposition']), 'HTML PPT 下载'))
  }

  skipCase({
    id: 'COL-001', module: '实时协作', name: 'WebSocket/STOMP 双客户端同步',
    target: '/ws/collaborate', input: '两个浏览器会话',
    expected: '光标、在线用户和编辑事件实时广播'
  }, '前端当前实际采用 2 秒轮询 + 自动保存，且网关未路由 /ws/collaborate；已通过 DOC-012～DOC-017 验证共享权限和跨账号编辑')

  skipCase({
    id: 'AIF-005', module: 'AI 文件', name: '回收站恢复',
    target: '/api/ai/files/restore', input: 'recycleId',
    expected: '恢复回收站对象'
  }, '公开 REST 删除流程执行确认后为永久删除，未提供“移入回收站”的 REST 入口，无法安全构造 recycleId')

  skipCase({
    id: 'AIF-006', module: 'AI 文件', name: '切换 MinIO 历史版本',
    target: '/api/ai/files/versions/switch', input: 'targetVersionId',
    expected: '管理员切换到历史版本'
  }, '本轮仅上传单一版本，且版本切换为高风险覆盖操作，不制造用户数据覆盖')

  if (annotationId && documentId) {
    await testCase({
      id: 'ANN-005', module: '批注', name: '删除批注',
      target: `/api/documents/${documentId}/annotations/${annotationId}`, input: 'annotationId',
      expected: '删除成功'
    }, () => http(`/api/documents/${documentId}/annotations/${annotationId}`, { method: 'DELETE', token: ownerToken }))
  }

  if (commentId && documentId) {
    await testCase({
      id: 'COM-005', module: '评论', name: '删除评论',
      target: `/api/documents/${documentId}/comments/${commentId}`, input: 'commentId',
      expected: '删除成功'
    }, () => http(`/api/documents/${documentId}/comments/${commentId}`, { method: 'DELETE', token: ownerToken }))
  }
}

let runError
try {
  await run()
} catch (error) {
  runError = error
  console.error(`[runner error] ${excerpt(error?.stack || error)}`)
} finally {
  await cleanupResources()
  const outputDir = path.join(repoRoot, '.local', 'test-results')
  fs.mkdirSync(outputDir, { recursive: true })
  const counts = results.reduce((acc, item) => {
    acc[item.status.toLowerCase()] = (acc[item.status.toLowerCase()] || 0) + 1
    return acc
  }, { pass: 0, fail: 0, skip: 0 })
  const artifact = {
    project: 'OmniDoc',
    runId,
    startedAt: runId,
    finishedAt: new Date().toISOString(),
    environment: {
      gatewayBase,
      webBase,
      model,
      node: process.version,
      platform: `${process.platform} ${process.arch}`
    },
    counts,
    results,
    runnerError: runError ? excerpt(runError?.stack || runError) : null
  }
  const outputPath = path.join(outputDir, `functional-test-${runId}.json`)
  fs.writeFileSync(outputPath, JSON.stringify(artifact, null, 2), 'utf8')
  console.log(`\nSummary: PASS=${counts.pass} FAIL=${counts.fail} SKIP=${counts.skip}`)
  console.log(`Result file: ${outputPath}`)
  if (strict && (counts.fail > 0 || runError)) process.exitCode = 1
}
