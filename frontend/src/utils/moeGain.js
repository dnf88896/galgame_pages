// 萌点变化检测 + 屏幕提示：全局单例。MoeToast.vue 订阅 moeGain 值，非空即在屏幕中心显示「+n萌点」并自动淡出。
// 统一入口 refreshMoe()：取最新 moe_points（传入 knownMoe 则直接用，否则拉 GET /auth/me），与 localStorage 基线
// （按用户 id 隔离）比较，增量>0 则提示「+n萌点」，并同步基线 + user store。
// 所有加分点（签到 +10 / 每日首次发帖 +10 / 每日首次评论 +5 / galgame 审核通过 +10 / 管理员上架 +10）成功后
// 统一调用 refreshMoe()，实现「检测萌点变化显示 +n 萌点」。
// App.vue refreshUser 在登录态恢复时首次调用建立基线（无基线不提示，避免把历史萌点误报为新增）。
import { ref } from 'vue'
import api from '../api'
import { user, setUser } from '../store/user'

const MOE_KEY = 'galforum_moe_baseline'

export const moeGain = ref(null)

// 基线按用户 id 隔离，避免多账号登录串号误报
function baselineKey() {
  const uid = user.value?.id
  return uid ? `${MOE_KEY}_${uid}` : MOE_KEY
}

export function showMoeGain(amount) {
  const n = Number(amount) || 0
  if (n <= 0) return
  moeGain.value = {
    amount: n,
    key: `${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
  }
}

// 通用萌点变化检测。knownMoe 传入时（调用方已持有最新值，如 App.refreshUser 拉的 /auth/me）不再发请求；
// 否则拉 GET /auth/me。与基线比较增量>0 弹提示；同步基线 + user store。返回最新 moe，失败返回 null。
export async function refreshMoe(knownMoe) {
  try {
    let moe = Number(knownMoe)
    if (!Number.isFinite(moe)) {
      const { data } = await api.get('/auth/me')
      moe = Number(data?.moe_points) || 0
    }
    const key = baselineKey()
    const raw = localStorage.getItem(key)
    const base = raw === null ? null : Number(raw) || 0
    if (base !== null && moe > base) {
      showMoeGain(moe - base)
    }
    localStorage.setItem(key, String(moe))
    if (user.value && Number(user.value.moe_points) !== moe) {
      setUser({ ...user.value, moe_points: moe })
    }
    return moe
  } catch {
    return null
  }
}
