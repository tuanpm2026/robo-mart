import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { useAuthStore } from '@/stores/useAuthStore'
import LoginModal from '../LoginModal.vue'

vi.mock('@/auth/authService', () => ({
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  renewToken: vi.fn().mockResolvedValue(null),
  getUser: vi.fn().mockResolvedValue(null),
  getAccessToken: vi.fn().mockReturnValue(null),
  loginCallback: vi.fn(),
  saveRedirectPath: vi.fn(),
  consumeRedirectPath: vi.fn().mockReturnValue('/'),
  subscribeToAuthEvents: vi.fn().mockReturnValue(vi.fn()),
}))

// Stub PrimeVue Dialog to render inline instead of teleporting
const DialogStub = {
  template: '<div v-if="visible"><slot /></div>',
  props: ['visible', 'modal', 'closable', 'header'],
}

function mountModal(visible = true) {
  return mount(LoginModal, {
    props: { visible },
    global: {
      plugins: [PrimeVue],
      stubs: {
        Dialog: DialogStub,
      },
    },
  })
}

describe('LoginModal', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('should render social login buttons', () => {
    const wrapper = mountModal()
    const buttons = wrapper.findAll('.login-modal__social-btn')
    expect(buttons.length).toBe(2)
    expect(wrapper.text()).toContain('Continue with Google')
    expect(wrapper.text()).toContain('Continue with GitHub')
  })

  it('should render email input and submit button', () => {
    const wrapper = mountModal()
    expect(wrapper.find('#login-email').exists()).toBe(true)
    expect(wrapper.text()).toContain('Continue with Email')
  })

  it('should render register link', () => {
    const wrapper = mountModal()
    expect(wrapper.text()).toContain("Don't have an account?")
    expect(wrapper.find('.login-modal__register-link').text()).toBe('Register')
  })

  it('should render divider between social and email', () => {
    const wrapper = mountModal()
    expect(wrapper.text()).toContain('or')
  })

  it('should show email validation error for empty email on submit', async () => {
    const wrapper = mountModal()
    const form = wrapper.find('.login-modal__form')
    await form.trigger('submit')
    expect(wrapper.find('.login-modal__field-error').text()).toBe('Email is required')
  })

  it('should show email validation error for invalid email format', async () => {
    const wrapper = mountModal()
    const input = wrapper.find('#login-email')
    await input.setValue('not-an-email')
    await input.trigger('blur')
    expect(wrapper.find('.login-modal__field-error').text()).toBe('Please enter a valid email')
  })

  it('should not show error for valid email', async () => {
    const wrapper = mountModal()
    const input = wrapper.find('#login-email')
    await input.setValue('test@example.com')
    await input.trigger('blur')
    expect(wrapper.find('.login-modal__field-error').exists()).toBe(false)
  })

  it('should show auth error from store', () => {
    const store = useAuthStore()
    store.error = 'Authentication failed'

    const wrapper = mountModal()
    expect(wrapper.text()).toContain('Authentication failed')
  })

  it('should not render content when not visible', () => {
    const wrapper = mountModal(false)
    expect(wrapper.find('.login-modal__content').exists()).toBe(false)
  })

  it('should call authStore.login with Google idp hint on Google button click', async () => {
    const store = useAuthStore()
    const loginSpy = vi.spyOn(store, 'login').mockResolvedValue()

    const wrapper = mountModal()
    const googleBtn = wrapper.findAll('.login-modal__social-btn')[0]!
    await googleBtn.trigger('click')

    expect(loginSpy).toHaveBeenCalledWith('google')
  })

  it('should call authStore.login with GitHub idp hint on GitHub button click', async () => {
    const store = useAuthStore()
    const loginSpy = vi.spyOn(store, 'login').mockResolvedValue()

    const wrapper = mountModal()
    const githubBtn = wrapper.findAll('.login-modal__social-btn')[1]!
    await githubBtn.trigger('click')

    expect(loginSpy).toHaveBeenCalledWith('github')
  })

  it('should call authStore.login with login_hint on email submit', async () => {
    const store = useAuthStore()
    const loginSpy = vi.spyOn(store, 'login').mockResolvedValue()

    const wrapper = mountModal()
    await wrapper.find('#login-email').setValue('test@example.com')
    await wrapper.find('.login-modal__form').trigger('submit')

    expect(loginSpy).toHaveBeenCalledWith(undefined, 'test@example.com')
  })

  it('should call authStore.register on register link click', async () => {
    const store = useAuthStore()
    const registerSpy = vi.spyOn(store, 'register').mockResolvedValue()

    const wrapper = mountModal()
    await wrapper.find('.login-modal__register-link').trigger('click')

    expect(registerSpy).toHaveBeenCalled()
  })
})
