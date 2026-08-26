import { reactive } from 'vue'

const KEY = 'galforum_settings'

const DEFAULTS = {
  theme: 'light',      // 'light' | 'dark'
  pageOpacity: 100,    // 60–100，页面内容透明度(%)
  bgBrightness: 100,   // 40–150，背景亮度(%)
  bgImage: '',         // '' 无背景 | dataURL 图片
}

function load() {
  try {
    return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY) || '{}') }
  } catch {
    return { ...DEFAULTS }
  }
}

export const settings = reactive(load())

/** 把设置应用到 DOM + 持久化：切 html.dark 类、写三个 CSS 变量、存 localStorage */
export function apply() {
  const el = document.documentElement
  el.classList.toggle('dark', settings.theme === 'dark')
  el.style.setProperty('--page-opacity', String((Number(settings.pageOpacity) || 100) / 100))
  el.style.setProperty('--bg-brightness', String((Number(settings.bgBrightness) || 100) / 100))
  if (settings.bgImage) {
    el.style.setProperty('--custom-bg', `url("${settings.bgImage}")`)
  } else {
    el.style.removeProperty('--custom-bg')
  }
  localStorage.setItem(KEY, JSON.stringify({ ...settings }))
}

export function setTheme(v) { settings.theme = v; apply() }
export function setPageOpacity(v) { settings.pageOpacity = v; apply() }
export function setBgBrightness(v) { settings.bgBrightness = v; apply() }
export function setBgImage(dataUrl) { settings.bgImage = dataUrl || ''; apply() }
