import { beforeEach, describe, expect, it } from 'vitest'
import { decodeJwt, getAuthenticatedUserId } from './jwt.js'

const token = (payload) => {
  const encoded = btoa(JSON.stringify(payload))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${encoded}.signature`
}

describe('authenticated conversation scope', () => {
  beforeEach(() => localStorage.clear())

  it('decodes an unpadded base64url payload and returns its userId', () => {
    const accessToken = token({ userId: 42, exp: Math.floor(Date.now() / 1000) + 60 })
    localStorage.setItem('accessToken', accessToken)

    expect(decodeJwt(accessToken)).toMatchObject({ userId: 42 })
    expect(getAuthenticatedUserId()).toBe('42')
  })

  it('rejects expired, malformed and ownerless tokens', () => {
    localStorage.setItem('accessToken', token({ userId: 'expired', exp: 1 }))
    expect(getAuthenticatedUserId()).toBeNull()

    localStorage.setItem('accessToken', 'not-a-jwt')
    expect(getAuthenticatedUserId()).toBeNull()

    localStorage.setItem('accessToken', token({ exp: Math.floor(Date.now() / 1000) + 60 }))
    expect(getAuthenticatedUserId()).toBeNull()
  })
})
