<template>
  <div class="page admin-reports-page">
    <el-page-header content="举报受理" @back="() => goBack(router)" />

    <el-card v-if="loading" style="margin-top: 16px">
      <el-skeleton :rows="6" animated />
    </el-card>

    <el-alert
      v-else-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      style="margin-top: 16px"
    />

    <el-empty
      v-else-if="!reports.length"
      style="margin-top: 32px"
      description="暂无举报"
    />

    <el-card v-else style="margin-top: 16px">
      <article v-for="r in reports" :key="r.id" class="report-item">
        <div class="report-top">
          <el-tag size="small" type="warning">{{ typeLabel(r) }}</el-tag>
          <span class="reporter">@{{ r.reporter?.username || '未知用户' }}</span>
          <span class="dot">·</span>
          <span class="time">{{ formatTime(r.created_at) }}</span>
        </div>
        <div class="report-target">{{ targetText(r) }}</div>
        <div class="report-bottom">
          <span class="report-reason" :class="{ empty: !r.reason }">
            原因：{{ r.reason || '未填写' }}
          </span>
          <div class="report-actions">
            <!-- 待处理才显示操作按钮 -->
            <template v-if="Number(r.status || 0) === 0">
              <el-button
                size="small"
                type="danger"
                :loading="handlingId === r.id && handlingAction === 'ban'"
                @click="openBanDialog(r)"
              >删除并封禁</el-button>
              <el-button
                size="small"
                type="danger"
                plain
                :loading="handlingId === r.id && handlingAction === 'delete'"
                @click="handleReport(r, 'delete')"
              >删除并警告</el-button>
              <el-button
                size="small"
                :loading="handlingId === r.id && handlingAction === 'ignore'"
                @click="handleReport(r, 'ignore')"
              >忽略</el-button>
            </template>
            <!-- 已处理显示状态标签 -->
            <el-tag v-else size="small" :type="Number(r.status) === 1 ? 'danger' : 'info'">
              {{ Number(r.status) === 1 ? '已处理' : '已忽略' }}
            </el-tag>
          </div>
          <el-button
            size="small"
            :disabled="postIdOf(r) == null"
            @click="goPost(r)"
          >
            {{ postIdOf(r) == null ? '目标已删除' : '查看原帖' }}
          </el-button>
        </div>
      </article>
    </el-card>

    <el-dialog v-model="banDialogVisible" title="删除并封禁" width="360px">
      <p style="margin: 0 0 8px; color: #606266">选择封禁时长：</p>
      <el-radio-group v-model="banDuration">
        <el-radio value="1">1 天</el-radio>
        <el-radio value="7">7 天</el-radio>
        <el-radio value="30">30 天</el-radio>
        <el-radio value="forever">永久</el-radio>
      </el-radio-group>
      <template #footer>
        <el-button @click="banDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="handlingAction === 'ban'" @click="confirmBan">确认封禁</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { goBack } from '../utils/navigation'
import { formatTime, getErrorMessage } from '../utils/format'

const router = useRouter()

const reports = ref([])
const loading = ref(false)
const loadError = ref('')

// 帖子 / 评论 类型中文标签
function typeLabel(r) {
  return r.target_type === 'reply' ? '评论' : '帖子'
}

// 目标摘要：帖子显示标题；评论显示「回复：」+ 内容（超 60 字截断加 …）
function targetText(r) {
  if (r.target_type === 'reply') {
    const content = r.reply?.content || ''
    const truncated = content.length > 60 ? content.slice(0, 60) + '…' : content
    return `回复：${truncated || '（内容已删除）'}`
  }
  return r.post?.title || '（帖子已删除）'
}

// 原帖 ID：帖子取 post.id；评论取 reply.post_id；目标已删除时为 null
function postIdOf(r) {
  if (r.target_type === 'reply') {
    const id = r.reply?.post_id
    return id == null ? null : Number(id)
  }
  const id = r.post?.id
  return id == null ? null : Number(id)
}

function goPost(r) {
  const id = postIdOf(r)
  if (id != null) router.push(`/post/${id}`)
}

const handlingId = ref(null)      // 正在处理的举报 id
const handlingAction = ref('')    // 'delete' | 'ignore' | 'ban'

async function handleReport(r, action) {
  const isDelete = action === 'delete'
  try {
    await ElMessageBox.confirm(
      isDelete
        ? `确定删除该${r.target_type === 'reply' ? '评论' : '帖子'}并警告作者吗？`
        : '确定忽略这条举报吗？（不删除内容）',
      isDelete ? '删除并警告' : '忽略举报',
      { type: 'warning', confirmButtonText: isDelete ? '删除并警告' : '忽略', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  handlingId.value = r.id
  handlingAction.value = action
  try {
    const { data } = await api.post(`/reports/${r.id}/handle`, { action })
    ElMessage.success(isDelete ? '已删除并警告作者' : '已忽略')
    // 本地更新状态，无需整页重拉
    r.status = data.status
    r.result = data.result
    r.handled_at = data.handled_at || r.handled_at
  } catch (e) {
    ElMessage.error(getErrorMessage(e, isDelete ? '处理失败' : '忽略失败'))
  } finally {
    handlingId.value = null
    handlingAction.value = ''
  }
}

// 封禁时长 dialog 状态
const banDialogVisible = ref(false)
const banTarget = ref(null)      // 当前要封禁的举报
const banDuration = ref('7')     // '1' | '7' | '30' | 'forever'
function openBanDialog(r) {
  banTarget.value = r
  banDuration.value = '7'
  banDialogVisible.value = true
}
async function confirmBan() {
  const r = banTarget.value
  if (!r) return
  const isPermanent = banDuration.value === 'forever'
  handlingId.value = r.id
  handlingAction.value = 'ban'
  try {
    const { data } = await api.post(`/reports/${r.id}/handle`, isPermanent
      ? { action: 'ban', ban_permanent: 'true' }
      : { action: 'ban', ban_days: banDuration.value })
    ElMessage.success(isPermanent ? '已删除并永久封禁' : `已删除并封禁作者${data.result ? '（' + data.result.replace('已删除并封禁作者', '') + '）' : ''}`)
    banDialogVisible.value = false
    r.status = data.status
    r.result = data.result
    r.handled_at = data.handled_at || r.handled_at
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '封禁失败'))
  } finally {
    handlingId.value = null
    handlingAction.value = ''
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await api.get('/reports')
    reports.value = Array.isArray(data) ? data : []
  } catch (e) {
    if (e.response?.status === 403) {
      loadError.value = '需要管理员权限。'
    } else {
      loadError.value = getErrorMessage(e, '加载失败')
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page.admin-reports-page {
  max-width: 760px;
}
.report-item {
  padding: 14px 0;
  border-bottom: 1px solid #f0f0f0;
}
.report-item:last-child {
  border-bottom: none;
}
.report-top {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
  color: #666;
  margin-bottom: 8px;
}
.reporter {
  color: #333;
  font-weight: 500;
}
.dot {
  color: #ccc;
}
.time {
  color: #999;
}
.report-target {
  font-size: 15px;
  color: #303133;
  margin-bottom: 8px;
  word-break: break-word;
}
.report-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.report-reason {
  font-size: 13px;
  color: #606266;
  flex: 1;
  min-width: 0;
  word-break: break-word;
}
.report-reason.empty {
  color: #909399;
}
.report-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
