export function decodeJwt(token) {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const payload = parts[1].padEnd(Math.ceil(parts[1].length / 4) * 4, '=')
    const decoded = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    return JSON.parse(decoded)
  } catch {
    return null
  }
}

export function getAuthenticatedUserId() {
  const token = localStorage.getItem('accessToken')
  if (!token) return null
  const payload = decodeJwt(token)
  if (!payload) return null
  if (Number.isFinite(payload.exp) && payload.exp * 1000 <= Date.now()) return null
  const userId = payload.userId
  if ((typeof userId !== 'string' && typeof userId !== 'number') || String(userId).trim() === '') {
    return null
  }
  return String(userId)
}

export function getUserRole() {
  const token = localStorage.getItem('accessToken')
  if (!token) return null
  const payload = decodeJwt(token)
  return payload ? payload.role : null
}

export function isAdmin() {
  return getUserRole() === 'ADMIN'
}
