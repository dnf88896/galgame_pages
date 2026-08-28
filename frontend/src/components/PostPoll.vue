<template>
  <!-- 有投票：完整投票卡片（所有人可见） -->
  <el-card v-if="polls.length" class="poll-card" style="margin-top: 16px">
    <template #header>
      <span class="poll-section-title">投票</span>
    </template>

    <div v-if="loading" class="poll-loading">
      <el-skeleton :rows="2" animated />
    </div>
    <el-alert
      v-else-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      class="poll-error"
    />
    <template v-else>
      <PollItem
        v-for="p in polls"
        :key="p.id"
        :poll="p"
        :is-owner="isOwner"
        @refresh="load"
        @edit="openEdit"
        @open-log="openLog"
      />
    </template>

    <div class="poll-footer">
      <el-button
        v-if="isOwner"
        type="primary"
        plain
        size="small"
        class="poll-create-btn"
        @click="openCreate"
      >创建投票</el-button>
    </div>
  </el-card>

  <!-- 无投票 + 楼主：小型创建入口（不占大位置；非楼主无投票则不渲染投票栏） -->
  <div v-else-if="isOwner && !loading" class="poll-create-inline" style="margin-top: 16px">
    <el-button type="primary" plain size="small" @click="openCreate">创建投票</el-button>
  </div>

  <PollCreatorDialog
    v-model="creatorVisible"
    :post-id="postId"
    :poll="editingPoll"
    @saved="load"
  />
  <PollLogDialog v-model="logVisible" :poll="logPoll" />
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import PollItem from './PollItem.vue'
import PollCreatorDialog from './PollCreatorDialog.vue'
import PollLogDialog from './PollLogDialog.vue'
import { requireLogin } from '../store/user'
import { getErrorMessage } from '../utils/format'

const props = defineProps({
  postId: { type: Number, required: true },
  isOwner: { type: Boolean, default: false },
})

const router = useRouter()

const polls = ref([])
const loading = ref(false)
const loadError = ref('')

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await api.get(`/posts/${props.postId}/polls`)
    polls.value = Array.isArray(data) ? data : data?.polls || []
  } catch (e) {
    loadError.value = getErrorMessage(e, '加载投票失败')
    polls.value = []
  } finally {
    loading.value = false
  }
}

// 创建 / 编辑弹窗
const creatorVisible = ref(false)
const editingPoll = ref(null)

function openCreate() {
  if (!requireLogin(router)) return
  editingPoll.value = null
  creatorVisible.value = true
}

function openEdit(p) {
  editingPoll.value = p
  creatorVisible.value = true
}

// 投票日志弹窗
const logVisible = ref(false)
const logPoll = ref(null)

function openLog(p) {
  logPoll.value = p
  logVisible.value = true
}

watch(
  () => props.postId,
  () => load(),
)

onMounted(load)
</script>

<style scoped>
.poll-section-title {
  font-size: 15px;
  font-weight: 600;
}
.poll-loading {
  padding: 4px 0;
}
.poll-error {
  margin: 0;
}
.poll-create-inline {
  display: flex;
  justify-content: center;
  padding: 6px 0;
}
.poll-footer {
  margin-top: 12px;
}
</style>
