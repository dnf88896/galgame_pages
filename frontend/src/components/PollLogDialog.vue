<template>
  <el-dialog
    v-model="visible"
    :title="`投票日志${poll ? '「' + poll.title + '」' : ''}`"
    width="560px"
    @open="load"
  >
    <div v-if="loading" class="log-loading">
      <el-skeleton :rows="4" animated />
    </div>
    <div v-else-if="!logs.length" class="log-empty">暂无投票记录</div>
    <ul v-else class="log-list">
      <li v-for="(log, i) in logs" :key="log.id ?? i" class="log-item">
        <el-avatar
          v-if="avatarOf(log)"
          :size="26"
          :src="resolveAssetUrl(avatarOf(log))"
          class="log-avatar"
        />
        <span class="log-who">{{ whoOf(log) }}</span>
        <span class="log-opt">投给了「{{ optOf(log) }}」</span>
        <span class="log-time">{{ timeOf(log) }}</span>
      </li>
    </ul>

    <el-pagination
      v-if="total > pageSize"
      class="log-pagination"
      layout="prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="page"
      @current-change="onPage"
    />
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import api from '../api'
import { formatTime, resolveAssetUrl } from '../utils/format'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  poll: { type: Object, default: null },
})

const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const logs = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)

// 打开弹窗或切换投票时回到第一页
watch(
  () => props.modelValue,
  (v) => {
    if (v) {
      page.value = 1
      logs.value = []
    }
  },
)

async function load() {
  if (!props.poll) return
  loading.value = true
  try {
    const { data } = await api.get(`/polls/${props.poll.id}/logs`, {
      params: { page: page.value, page_size: pageSize.value },
    })
    // 后端契约: { "logs": [{ id, created, user:{id,nickname,avatar_url}, option }], "total": N }
    logs.value = data?.logs || []
    total.value = data?.total != null ? Number(data.total) : logs.value.length
  } catch {
    logs.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onPage(p) {
  page.value = p
  load()
}

// 日志条目字段访问(契约: user.nickname / option / created)
function whoOf(log) {
  return log.user?.nickname || log.nickname || log.username || '用户'
}

function avatarOf(log) {
  return log.user?.avatar_url || log.avatar_url || null
}

function optOf(log) {
  const o = log.option
  if (o == null) return '?'
  if (typeof o === 'string') return o
  return o.text || o.option_text || '?'
}

function timeOf(log) {
  const t = log.created || log.created_at || log.vote_time || null
  return t ? formatTime(t) : ''
}
</script>

<style scoped>
.log-loading {
  padding: 8px 0;
}
.log-empty {
  text-align: center;
  color: #909399;
  padding: 24px 0;
}
.log-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 380px;
  overflow-y: auto;
}
.log-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
  font-size: 13px;
}
.log-item:last-child {
  border-bottom: none;
}
.log-avatar {
  flex-shrink: 0;
}
.log-who {
  flex-shrink: 0;
  color: #409eff;
  font-weight: 500;
}
.log-opt {
  flex: 1;
  min-width: 0;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.log-time {
  flex-shrink: 0;
  color: #909399;
}
.log-pagination {
  margin-top: 12px;
  justify-content: center;
}
</style>
