<template>
  <div class="page">
    <div class="add-center">
      <div class="add-head">
        <el-button link type="primary" @click="goBack">← 返回</el-button>
        <h1 class="add-title">提交制作人员信息</h1>
      </div>

      <!-- 权限守卫：未登录只显示提示，不渲染表单 -->
      <div v-if="!loggedIn" class="add-denied">
        <p>请先登录后提交制作人员信息。</p>
        <el-button type="primary" @click="goLogin">去登录</el-button>
      </div>

      <div v-else class="add-card">
        <el-form :model="form" label-width="100px" class="add-form">
          <el-form-item label="名称" required>
            <el-input
              v-model="form.name"
              placeholder="制作人员姓名（如 麻枝准 / 折戸伸治）"
              maxlength="200"
              show-word-limit
            />
          </el-form-item>

          <el-form-item label="简介">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="5"
              placeholder="这位制作人员参与过什么作品、擅长什么…"
              maxlength="2000"
              show-word-limit
            />
          </el-form-item>

          <el-form-item label="封面图片">
            <div class="add-cover">
              <el-upload
                :show-file-list="false"
                :http-request="uploadImage"
                accept="image/*"
              >
                <el-button type="primary" plain>选择封面图片</el-button>
              </el-upload>
              <div v-if="imagePreview" class="add-cover-preview">
                <img :src="imagePreview" alt="封面预览" />
                <el-button link type="danger" @click="removeCover">移除</el-button>
              </div>
            </div>
            <div class="add-hint">支持 jpg / png / gif / webp，上传后作为封面展示</div>
          </el-form-item>

          <el-form-item label="关联角色">
            <el-select
              v-model="form.characterIds"
              multiple
              collapse-tags
              filterable
              placeholder="选择关联角色（可不选）"
              class="add-tags-select"
            >
              <el-option v-for="c in characterOptions" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="submit">{{ isAdmin ? '提交' : '提交审核' }}</el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { user } from '../store/user'
import { refreshMoe } from '../utils/moeGain'
import { getErrorMessage, resolveAssetUrl } from '../utils/format'
import { goBack as navGoBack } from '../utils/navigation'

const route = useRoute()
const router = useRouter()

// 登录用户（有 id）才能提交制作人员信息；等级来自 store，认证后实时刷新
const isAdmin = computed(() => Number(user.value?.admin_level) > 0)
const loggedIn = computed(() => !!user.value?.id)

// 未登录：跳登录页，登录成功后回跳当前提交页
function goLogin() {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

const form = reactive({
  name: '',
  description: '',
  image: '',
  characterIds: [],
})
const imagePreview = ref('')
const submitting = ref(false)

// 关联角色下拉选项：GET /characters 返回数组（不是 {data:...} 包一层），失败兜底空数组不影响提交
const characterOptions = ref([])
async function loadCharacterOptions() {
  try {
    const { data } = await api.get('/characters')
    characterOptions.value = Array.isArray(data) ? data : []
  } catch (e) {
    // 拉取失败不阻断提交（关联角色可空）
  }
}

function removeCover() {
  form.image = ''
  imagePreview.value = ''
}

// 自定义 el-upload 上传：multipart 字段 file → POST /staffs/image → { url }
async function uploadImage(options) {
  const file = options?.file
  if (!file) return
  const fd = new FormData()
  fd.append('file', file)
  try {
    const { data } = await api.post('/staffs/image', fd)
    form.image = data.url
    imagePreview.value = resolveAssetUrl(data.url)
    ElMessage.success('封面上传成功')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '封面上传失败'))
  }
}

// 返回按钮：有站内上一页就真后退，否则兜底替换到下方目标页
// （判断依据与 replace 兜底的原因见 utils/navigation.js）
function goBack() {
  navGoBack(router, '/staff')
}

async function submit() {
  const name = form.name.trim()
  if (!name) {
    ElMessage.error('请填写制作人员名称。')
    return
  }
  const payload = {
    name,
    description: form.description.trim(),
    image: form.image,
    character_ids: form.characterIds,
  }
  submitting.value = true
  try {
    await api.post('/staffs', payload)
    if (isAdmin.value) {
      ElMessage.success('添加成功')
      // 管理员创建即上架 → 提交者（自己）+10 萌点；refreshMoe 通用检测提示并同步 store/基线
      refreshMoe()
    } else {
      ElMessage.success('已提交，等待管理员审核')
    }
    router.push('/staff')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '提交失败'))
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadCharacterOptions()
})
</script>

<style scoped>
.page {
  width: 100%;
}
.add-center {
  max-width: 720px;
  margin: 0 auto;
  padding: 24px 16px 0;
}
.add-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.add-title {
  margin: 0;
  font-size: 22px;
}
.add-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 24px;
  margin-bottom: 16px;
}
.add-denied {
  text-align: center;
  padding: 48px 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}
.add-denied p {
  margin: 0 0 16px;
  color: #606266;
}
.add-tags-select {
  width: 100%;
}
.add-cover {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}
.add-cover-preview {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.add-cover-preview img {
  width: 120px;
  height: 150px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid #e4e7ed;
  background: #f5f7fa;
}
.add-hint {
  color: #b0b3b8;
  font-size: 12px;
  margin-top: 4px;
}
</style>
