<template>
  <div class="mention-textarea">
    <textarea
      ref="taRef"
      class="mention-input"
      :value="modelValue"
      :placeholder="placeholder"
      :rows="rows"
      :maxlength="maxlength"
      @input="onInput"
      @keydown="onKeydown"
      @blur="onBlur"
    />
    <span v-if="showWordLimit" class="mention-word-count">{{ modelValue.length }}/{{ maxlength }}</span>
    <ul v-if="showList" class="mention-list">
      <li
        v-for="(u, i) in filtered"
        :key="u.id"
        :class="{ active: i === activeIndex }"
        @mousedown.prevent
        @click="pick(u)"
        @mouseenter="activeIndex = i"
      >
        <el-avatar v-if="u.avatar_url" :src="resolveAssetUrl(u.avatar_url)" :size="22" />
        <el-avatar v-else :size="22" class="mention-avatar">{{ firstOf(u) }}</el-avatar>
        <span class="mention-nick">{{ u.nickname || u.username }}</span>
        <span class="mention-user">@{{ u.username }}</span>
      </li>
      <li v-if="!filtered.length" class="mention-none">
        {{ followingLoaded && !following.length ? '你还没有关注任何用户' : '没有匹配的关注用户' }}
      </li>
    </ul>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import api from '../api'
import { user } from '../store/user'
import { resolveAssetUrl } from '../utils/format'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  rows: { type: [Number, String], default: 4 },
  maxlength: { type: [Number, String], default: undefined },
  showWordLimit: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])

const taRef = ref(null)
const following = ref([])
const followingLoaded = ref(false)
// 当前 @ 触发点：{ idx: 最后一个 @ 的位置, query: @ 到光标之间的待匹配词 }
const trigger = ref(null)
const activeIndex = ref(0)

const showList = computed(() => !!trigger.value && !!user.value)
const filtered = computed(() => {
  if (!trigger.value) return []
  const q = trigger.value.query.toLowerCase()
  if (!q) return following.value
  return following.value.filter(
    (u) =>
      (u.nickname || '').toLowerCase().includes(q) ||
      (u.username || '').toLowerCase().includes(q),
  )
})

// 进入输入框时拉一次「我关注的人」列表（昵称/用户名/头像）
onMounted(async () => {
  if (!user.value) return
  try {
    const { data } = await api.get(`/users/${user.value.id}/following`)
    following.value = Array.isArray(data) ? data : []
  } catch {
    following.value = []
  } finally {
    followingLoaded.value = true
  }
})

function onInput(e) {
  // 用 DOM 的同步值（emit 后 props.modelValue 要到下一 tick 才更新，
  // 直接读 props 会导致刚输入的 @ 检测不到，列表晚一拍才出现）
  const value = e.target.value
  emit('update:modelValue', value)
  detectTrigger(value)
}

/** 光标前最后一个 @ 且非邮箱/URL 场景 → 进入补全；否则关闭 */
function detectTrigger(text) {
  const ta = taRef.value
  const pos = ta ? ta.selectionStart ?? text.length : text.length
  const before = text.slice(0, pos)
  const idx = before.lastIndexOf('@')
  if (idx < 0) {
    trigger.value = null
    return
  }
  // @ 前是词字符（中英文/数字/下划线）→ 可能是邮箱/URL，不触发
  if (idx > 0 && /[\p{L}\p{N}_]/u.test(before[idx - 1])) {
    trigger.value = null
    return
  }
  const query = before.slice(idx + 1)
  // 查询词含空白/非法字符 → 补全已结束
  if (/[^\p{L}\p{N}_]/u.test(query)) {
    trigger.value = null
    return
  }
  trigger.value = { idx, query }
  activeIndex.value = 0
}

function onKeydown(e) {
  if (!showList.value) return
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (filtered.value.length) activeIndex.value = (activeIndex.value + 1) % filtered.value.length
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (filtered.value.length) {
      activeIndex.value = (activeIndex.value - 1 + filtered.value.length) % filtered.value.length
    }
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const u = filtered.value[activeIndex.value]
    if (u) pick(u)
  } else if (e.key === 'Escape') {
    e.preventDefault()
    trigger.value = null
  }
}

/** 点击/回车选中：把 @到光标 替换为 @用户名 + 空格，光标定位到其后 */
function pick(u) {
  if (!trigger.value) return
  const ta = taRef.value
  // DOM 值始终最新（props 可能还没到下一 tick）
  const text = ta ? ta.value : props.modelValue
  const start = trigger.value.idx
  const end = ta ? ta.selectionStart ?? text.length : text.length
  const newText = text.slice(0, start) + '@' + u.username + ' ' + text.slice(end)
  emit('update:modelValue', newText)
  trigger.value = null
  const pos = start + 1 + u.username.length + 1
  nextTick(() => {
    ta.focus()
    ta.setSelectionRange(pos, pos)
  })
}

function onBlur() {
  // 延迟关闭，给列表项点击留时间
  setTimeout(() => {
    trigger.value = null
  }, 150)
}

function firstOf(u) {
  return (u.nickname || u.username || '?').slice(0, 1).toUpperCase()
}
</script>

<style scoped>
.mention-textarea {
  position: relative;
  /* 关键：在 el-form-item__content(flex 容器)里必须给满宽，
     否则 flex 子项收缩成内容宽(小方框)，内部 textarea 的 100% 也跟着缩 */
  width: 100%;
}
.mention-input {
  display: block;
  width: 100%;
  box-sizing: border-box;
  padding: 8px 12px;
  padding-bottom: 26px; /* 给字数统计留空间 */
  resize: vertical;
  font: inherit;
  line-height: 1.5;
  color: #606266;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  outline: none;
  transition: border-color 0.2s;
}
.mention-input:focus {
  border-color: #409eff;
}
.mention-input::placeholder {
  color: #a8abb2;
}
.mention-word-count {
  position: absolute;
  right: 10px;
  bottom: 8px;
  font-size: 12px;
  color: #c0c4cc;
  pointer-events: none;
}
.mention-list {
  position: absolute;
  bottom: 100%;
  left: 0;
  right: 0;
  z-index: 20;
  margin: 0 0 4px;
  padding: 4px 0;
  list-style: none;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
  max-height: 200px;
  overflow-y: auto;
}
.mention-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 14px;
  color: #303133;
}
.mention-list li.active {
  background: #f5f7fa;
}
.mention-avatar {
  background: #409eff;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}
.mention-nick {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mention-user {
  margin-left: auto;
  color: #909399;
  font-size: 13px;
  flex-shrink: 0;
}
.mention-none {
  justify-content: center;
  color: #909399;
  cursor: default;
}
</style>
