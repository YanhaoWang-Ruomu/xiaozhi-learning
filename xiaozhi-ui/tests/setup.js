import { beforeEach, vi } from 'vitest'

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  // A forgotten mock must fail rather than contact the backend or a cloud service.
  vi.stubGlobal('fetch', vi.fn(() => {
    throw new Error('Unexpected real fetch: provide an explicit test response')
  }))
})
