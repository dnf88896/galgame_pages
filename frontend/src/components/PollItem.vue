<template>
  <div class="poll-item">
    <div class="poll-head">
      <div class="poll-head-left">
        <span class="poll-title">{{ poll.title }}</span>
        <el-tag
          :type="isPollEnded ? 'info' : 'success'"
          size="small"
          effect="light"
        >{{ isPollEnded ? '已结束' : '进行中' }}</el-tag>
        <span v-if="poll.has_voted" class="poll-voted-tag">已投 ✓</span>
      </div>
      <div v-if="isOwner" class="poll-manage">
        <el-button link size="small" type="primary" @click="$emit('edit', poll)">编辑</el-button>
        <el-button link size="small" type="primary" @click="$emit('open-log', poll)">投票日志</el-button>
        <el-button link size="small" type="danger" :loading="deleting" @click="removePoll">删除</el-button>
      </div>
    </div>

    <p v-if="poll.description" class="poll-desc">{{ poll.description }}</p>

    <div class="poll-meta">
      <span class="poll-chip">{{ poll.type === 'multiple' ? `多选 ${poll.min_choice}-${poll.max_choice} 项` : '单选' }}</span>
      <span class="poll-chip">{{ visibilityLabel }}</span>
      <span class="poll-chip">{{ poll.can_change_vote ? '可改票' : '不可改票' }}</span>
      <span v-if="poll.is_anonymous" class="poll-chip">匿名</span>
      <span v-if="poll.deadline" class="poll-chip">截止 {{ formatTime(poll.deadline) }}</span>
    </div>

    <el-radio-group
      v-if="poll.type === 'single'"
      v-model="selectedSingle"
      class="poll-options"
      :disabled="!votable"
    >
      <el-radio
        v-for="opt in poll.options"
        :key="opt.id"
        :value="opt.id"
        class="poll-option"
        :class="{ 'is-voted': opt.is_voted }"
      >
        <span class="option-text">{{ opt.text }}</span>
        <span v-if="canViewResults" class="option-stats">
          <span class="option-bar"><span class="option-bar-inner" :style="{ width: percent(opt) }"></span></span>
          <span class="option-count">{{ opt.vote_count ?? 0 }} 票 · {{ percent(opt) }}</span>
        </span>
      </el-radio>
    </el-radio-group>

    <el-checkbox-group
      v-else
      v-model="selectedMultiple"
      class="poll-options"
      :disabled="!votable"
    >
      <el-checkbox
        v-for="opt in poll.options"
        :key="opt.id"
        :value="opt.id"
        class="poll-option"
        :class="{ 'is-voted': opt.is_voted }"
      >
        <span class="option-text">{{ opt.text }}</span>
        <span v-if="canViewResults" class="option-stats">
          <span class="option-bar"><span class="option-bar-inner" :style="{ width: percent(opt) }"></span></span>
          <span class="option-count">{{ opt.vote_count ?? 0 }} 票 · {{ percent(opt) }}</span>
        </span>
      </el-checkbox>
    </el-checkbox-group>

    <div class="poll-actions">
      <template v-if="!isPollEnded">
        <el-button
          v-if="!poll.has_voted"
          type="primary"
          size="small"
          :loading="voting"
          :disabled="!canSubmit"
          @click="submitVote"
        >投票</el-button>
        <el-button
          v-else-if="poll.can_change_vote"
          type="primary"
          size="small"
          :loading="voting"
          :disabled="!canSubmit"
          @click="submitVote"
        >修改投票</el-button>
      </template>
      <span v-if="poll.has_voted" class="poll-voted-hint">
        {{ isPollEnded ? '已截止' : poll.can_change_vote ? '可修改投票' : '不可改票' }}
      </span>
      <el-button
        v-if="!canViewResults && !isOwner"
        link
        size="small"
        type="primary"
        @click="viewResultsHint"
      >查看结果</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { requireLogin } from '../store/user'
import { formatTime, getErrorMessage } from '../utils/format'

const props = defineProps({
  poll: { type: Object, required: true },
  isOwner: { type: Boolean, default: false },
})

const emit = defineEmits(['refresh', 'edit', 'open-log'])

const router = useRouter()

// 当前选中项:单选存 id,多选存 id 数组
const selectedSingle = ref(null)
const selectedMultiple = ref([])

// 已投票用户进入时预选其已选项(改票场景下可见)
watch(
  () => props.poll,
  (p) => {
    if (!p) return
    const voted = (p.options || []).filter((o) => o.is_voted).map((o) => o.id)
    if (p.type === 'single') selectedSingle.value = voted[0] ?? null
    else selectedMultiple.value = [...voted]
  },
  { immediate: true },
)

// 是否已结束:状态 closed 或已过截止时间
const isPollEnded = computed(() => {
  const p = props.poll
  if (!p) return false
  if (p.status === 'closed') return true
  if (p.deadline) {
    const t = new Date(p.deadline).getTime()
    if (!Number.isNaN(t) && t < Date.now()) return true
  }
  return false
})

// 能否查看结果:楼主始终可见;结束可见;visibility 决定
const canViewResults = computed(() => {
  const p = props.poll
  if (!p) return false
  if (props.isOwner) return true
  if (isPollEnded.value) return true
  const vis = p.result_visibility
  if (vis === 'always') return true
  if (vis === 'after_vote' && p.has_voted) return true
  return false
})

// 选项是否可选:未结束且(未投 或 可改票)
const votable = computed(() => {
  const p = props.poll
  return !isPollEnded.value && !(p.has_voted && !p.can_change_vote)
})

// 当前选择是否满足单选/多选约束
const canSubmit = computed(() => {
  const p = props.poll
  if (!p) return false
  if (p.type === 'single') return selectedSingle.value != null
  const n = selectedMultiple.value.length
  return n >= p.min_choice && n <= p.max_choice
})

const visibilityLabel = computed(() => {
  const map = {
    always: '所有人可见',
    after_vote: '投票后可见',
    after_deadline: '截止后可见',
  }
  return map[props.poll.result_visibility] || '所有人可见'
})

function percent(opt) {
  const total = Number(props.poll.vote_count) || 0
  if (!total) return '0%'
  return `${Math.round((Number(opt.vote_count || 0) / total) * 100)}%`
}

const voting = ref(false)

async function submitVote() {
  if (!requireLogin(router)) return
  const p = props.poll
  const arr =
    p.type === 'single'
      ? selectedSingle.value == null
        ? []
        : [selectedSingle.value]
      : [...selectedMultiple.value]
  if (!arr.length) {
    ElMessage.warning('请选择选项')
    return
  }
  voting.value = true
  try {
    await api.post(`/polls/${p.id}/vote`, { option_id_array: arr })
    ElMessage.success(p.has_voted ? '投票已修改' : '投票成功')
    emit('refresh')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '投票失败'))
  } finally {
    voting.value = false
  }
}

// 无权查看结果时点击「查看结果」的提示
function viewResultsHint() {
  if (props.poll.result_visibility === 'after_deadline') ElMessage.info('投票结束后可查看结果')
  else ElMessage.info('投票后即可查看结果')
}

const deleting = ref(false)

async function removePoll() {
  try {
    await ElMessageBox.confirm(
      '确定删除这个投票吗？删除后不可恢复。',
      '删除投票',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  deleting.value = true
  try {
    await api.delete(`/polls/${props.poll.id}`)
    ElMessage.success('投票已删除')
    emit('refresh')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '删除投票失败'))
  } finally {
    deleting.value = false
  }
}
</script>

<style scoped>
.poll-item {
  padding: 4px 0;
}
.poll-item + .poll-item {
  border-top: 1px solid #f0f0f0;
  padding-top: 14px;
}
.poll-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.poll-head-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.poll-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.poll-voted-tag {
  font-size: 12px;
  color: #409eff;
}
.poll-manage {
  flex-shrink: 0;
}
.poll-desc {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
  color: #666;
  margin: 0 0 8px;
  font-size: 13px;
}
.poll-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.poll-chip {
  color: #888;
  background: #f5f7fa;
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 12px;
}
.poll-options {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}
.poll-option {
  display: flex;
  align-items: flex-start;
  height: auto;
  margin-right: 0;
  padding: 6px 10px;
  border-radius: 8px;
  border: 1px solid #ebeef5;
  background: #fff;
  transition: border-color 0.2s;
}
.poll-option:hover {
  border-color: #409eff;
}
.poll-option.is-voted {
  border-color: #409eff;
  background: #f0f7ff;
}
.poll-option :deep(.el-radio__label),
.poll-option :deep(.el-checkbox__label) {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding-right: 8px;
  white-space: normal;
}
.option-text {
  font-size: 14px;
  color: #303133;
  line-height: 1.4;
}
.option-stats {
  display: flex;
  align-items: center;
  gap: 8px;
}
.option-bar {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: #f0f2f5;
  overflow: hidden;
}
.option-bar-inner {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: #409eff;
  transition: width 0.3s;
}
.option-count {
  flex-shrink: 0;
  font-size: 12px;
  color: #909399;
}
.poll-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
}
.poll-voted-hint {
  font-size: 13px;
  color: #909399;
}
</style>
