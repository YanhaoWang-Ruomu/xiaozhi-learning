import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import EngineeringLab from '../src/components/EngineeringLab.vue'
import { apiFetch } from '../src/api/auth.js'
vi.mock('../src/api/auth.js', () => ({ apiFetch: vi.fn() }))
let wrapper
const cp = { id: '11111111-1111-1111-1111-111111111111', phase: 'PENDING_CONFIRMATION', version: 2, requirements: {}, draftId: 'draft1', draft: { message: '尚未预约' } }
const button = text => wrapper.findAll('button').find(b => b.text() === text)
beforeEach(() => { localStorage.clear(); apiFetch.mockReset(); vi.spyOn(window,'confirm').mockReturnValue(false) })
afterEach(() => wrapper?.unmount())
it('resumes only the signed-in account checkpoint without writes', async () => {
 localStorage.setItem('xiaozhi.workflow.owner',cp.id)
 apiFetch.mockResolvedValue({ ok:true,json:async()=>cp })
 wrapper=mount(EngineeringLab,{props:{userId:'owner'}});await flushPromises()
 expect(wrapper.text()).toContain('PENDING_CONFIRMATION');expect(apiFetch).toHaveBeenCalledTimes(1)
 expect(apiFetch.mock.calls[0][1].method).toBe('GET')
 await button('确认演示预约').trigger('click');expect(apiFetch).toHaveBeenCalledTimes(1)
})
it('does not restore another account workflow', async () => {
 localStorage.setItem('xiaozhi.workflow.other',cp.id)
 wrapper=mount(EngineeringLab,{props:{userId:'owner'}});await flushPromises();expect(apiFetch).not.toHaveBeenCalled()
})
it('explicit confirmation sends one mutation and updates real status', async () => {
 localStorage.setItem('xiaozhi.workflow.owner',cp.id)
 apiFetch.mockResolvedValueOnce({ok:true,json:async()=>cp}).mockResolvedValueOnce({ok:true,json:async()=>({...cp,phase:'CONFIRMED'})})
 wrapper=mount(EngineeringLab,{props:{userId:'owner'}});await flushPromises();window.confirm.mockReturnValue(true)
 await button('确认演示预约').trigger('click');await flushPromises()
 expect(apiFetch.mock.calls[1][0]).toContain('/confirm');expect(JSON.parse(apiFetch.mock.calls[1][1].body).confirmed).toBe(true);expect(wrapper.text()).toContain('CONFIRMED')
})
it('shows failed retrieval without inventing a result', async () => {
 apiFetch.mockResolvedValue({ok:false,status:503});wrapper=mount(EngineeringLab,{props:{userId:'owner'}})
 await button('运行检索').trigger('click');await flushPromises();expect(wrapper.text()).toContain('HTTP 503');expect(wrapper.findAll('article')).toHaveLength(0)
})

it('dedicated ranking runs only after explicit selection and click', async () => {
 apiFetch.mockResolvedValue({ok:true,json:async()=>({accepted:[],scoreType:'dedicated_relevance',fallback:''})})
 wrapper=mount(EngineeringLab,{props:{userId:'owner'}})
 await wrapper.find('select').setValue('dedicated');expect(apiFetch).not.toHaveBeenCalled()
 await button('运行检索').trigger('click');await flushPromises()
 expect(apiFetch.mock.calls[0][0]).toContain('mode=dedicated');expect(wrapper.text()).toContain('dedicated_relevance')
})
