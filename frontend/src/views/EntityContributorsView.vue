<template>
  <div class="gal-contributors-center">
    <!-- 加载中骨架屏 -->
    <div v-if="loading" class="gal-contributors-skel">
      <el-skeleton :rows="8" animated />
    </div>

    <!-- 加载失败 -->
    <el-alert
      v-else-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      class="gal-contributors-error"
    />

    <!-- 列表 / 空态 -->
    <template v-else>
      <div class="gal-contributors-head">
        <el-button link type="primary" @click="goBack">← 返回</el-button>
        <span class="gal-contributors-title">条目贡献者（{{ list.length }}）</span>
      </div>

      <div v-if="list.length" class="gal-contributors-card">
        <div
          v-for="u in list"
          :key="u.id"
          class="gal-contributors-item"
          @click="goUser(u.id)"
        >
          <div class="gal-contributors-avatar">
            <img
              v-if="u.avatar_url && !u._avatarError"
              :src="resolveAssetUrl(u.avatar_url)"
              alt=""
              @error="avatarImgError(u)"
            />
            <span v-else>{{ (u.nickname || u.username || '?').slice(0, 1).toUpperCase() }}</span>
          </div>
          <div class="gal-contributors-info">
            <span class="gal-contributors-nick">{{ u.nickname || u.username }}</span>
            <span class="gal-contributors-username">@{{ u.username }}</span>
          </div>
          <span class="gal-contributors-time">贡献于 {{ formatTime(u.created_at) }}</span>
        </div>
      </div>
      <div v-else class="gal-contributors-empty">暂无贡献者</div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from '../api'
import { getErrorMessage, resolveAssetUrl, formatTime } from '../utils/format'
import { goBack as navGoBack } from '../utils/navigation'

const route = useRoute()
const router = useRouter()

// 依据路由名映射实体类型（galgame-contributors → galgame 等）；未知 name 兜底 galgame
const ENTRY_TYPE_BY_NAME = {
  'galgame-contributors': 'galgame',
  'company-contributors': 'company',
  'staff-contributors': 'staff',
  'character-contributors': 'character',
}
const entryType = ENTRY_TYPE_BY_NAME[route.name] || 'galgame'
const entryId = route.params.id

const list = ref([])
const loading = ref(false)
const loadError = ref('')

// 贡献者列表：GET /api/entity-contributors?entry_type=&entry_id=（公开接口，未登录也能访问）
async function loadContributors() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await api.get('/entity-contributors', {
      params: { entry_type: entryType, entry_id: entryId },
    })
    list.value = Array.isArray(data) ? data : []
  } catch (e) {
    loadError.value = getErrorMessage(e, '加载失败')
  } finally {
    loading.value = false
  }
}

// 点击贡献者 → 进入用户主页
function goUser(id) {
  if (id == null) return
  router.push('/user/' + id)
}

// 头像加载失败时回退到文字占位
function avatarImgError(u) {
  if (u) u._avatarError = true
}

// 返回按钮：有站内上一页就真后退，否则兜底替换到下方目标页
// （判断依据与 replace 兜底的原因见 utils/navigation.js）
function goBack() {
  navGoBack(router, '/' + entryType)
}

onMounted(loadContributors)
</script>
