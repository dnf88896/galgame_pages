<template>
  <div class="page">
    <div class="add-center">
      <div class="add-head">
        <el-button link type="primary" @click="goBack">← 返回</el-button>
        <h1 class="add-title">添加 Galgame</h1>
      </div>

      <!-- 权限守卫：非管理员只显示提示，不渲染表单 -->
      <div v-if="!isAdmin" class="add-denied">
        <p>需要管理员权限才能添加 Galgame。</p>
        <el-button type="primary" @click="goBack">返回</el-button>
      </div>

      <div v-else class="add-card">
        <el-form :model="form" label-width="100px" class="add-form">
          <el-form-item label="名称" required>
            <el-input
              v-model="form.name"
              placeholder="Galgame 名称"
              maxlength="200"
              show-word-limit
            />
          </el-form-item>

          <el-form-item label="标签" required>
            <el-select
              v-model="form.tags"
              multiple
              collapse-tags
              placeholder="选择标签（至少 1 个）"
              class="add-tags-select"
            >
              <el-option-group v-for="g in tagGroups" :key="g.label" :label="g.label">
                <el-option v-for="opt in g.options" :key="opt.key" :label="opt.label" :value="opt.key" />
              </el-option-group>
            </el-select>
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

          <el-form-item label="简介">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="5"
              placeholder="这部作品讲什么…"
              maxlength="2000"
              show-word-limit
            />
          </el-form-item>

          <el-form-item label="制作人员">
            <el-input
              v-model="form.staff"
              placeholder="如 Key / Visual Art's"
              maxlength="200"
            />
          </el-form-item>

          <el-form-item label="资源链接">
            <div class="add-links">
              <div v-for="(link, i) in links" :key="i" class="add-link-row">
                <el-input
                  v-model="link.label"
                  placeholder="标签（如「官网」）"
                  class="add-link-label"
                  maxlength="30"
                />
                <el-input
                  v-model="link.url"
                  placeholder="https://…"
                  class="add-link-url"
                />
                <el-button
                  link
                  type="danger"
                  :aria-label="`删除链接 ${i + 1}`"
                  @click="removeLink(i)"
                >删除</el-button>
              </div>
              <el-button type="primary" plain size="small" class="add-link-btn" @click="addLink">+ 添加链接</el-button>
            </div>
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="submit">提交</el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { user } from '../store/user'
import { fetchTagStructure, getErrorMessage, resolveAssetUrl } from '../utils/format'

const router = useRouter()

// 管理员（admin_level > 0）才能添加 galgame；等级来自 store，认证后实时刷新
const isAdmin = computed(() => Number(user.value?.admin_level) > 0)

const form = reactive({
  name: '',
  tags: [],
  image: '',
  description: '',
  staff: '',
})
const links = ref([{ label: '', url: '' }])
const imagePreview = ref('')
const submitting = ref(false)

// 标签结构：从 galgame-resource 大类下按前缀分 4 组（类型 / 语言 / 平台 / 作品）
const tagCategories = ref([])
const tagGroups = computed(() => {
  const gal = (tagCategories.value || []).find((c) => c && c.key === 'galgame-resource')
  const sections = Array.isArray(gal?.sections) ? gal.sections : []
  return [
    { label: '类型', prefix: 'gg-type-' },
    { label: '语言', prefix: 'gg-lang-' },
    { label: '平台', prefix: 'gg-plat-' },
    { label: '作品', prefix: 'gg-work-' },
  ]
    .map((g) => ({
      label: g.label,
      options: sections
        .filter((s) => s && s.key && s.key.startsWith(g.prefix))
        .map((s) => ({ key: s.key, label: s.label })),
    }))
    .filter((g) => g.options.length)
})

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/galgame')
}

function addLink() {
  links.value.push({ label: '', url: '' })
}

function removeLink(i) {
  links.value.splice(i, 1)
}

function removeCover() {
  form.image = ''
  imagePreview.value = ''
}

// 自定义 el-upload 上传：multipart 字段 file → POST /galgames/image → { url }
async function uploadImage(options) {
  const file = options?.file
  if (!file) return
  const fd = new FormData()
  fd.append('file', file)
  try {
    const { data } = await api.post('/galgames/image', fd)
    form.image = data.url
    imagePreview.value = resolveAssetUrl(data.url)
    ElMessage.success('封面上传成功')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '封面上传失败'))
  }
}

async function submit() {
  const name = form.name.trim()
  if (!name) {
    ElMessage.error('请填写 Galgame 名称。')
    return
  }
  if (!form.tags.length) {
    ElMessage.error('请至少选择 1 个标签。')
    return
  }
  const payload = {
    name,
    description: form.description.trim(),
    image: form.image,
    staff: form.staff.trim(),
    links: links.value
      .map((l) => ({ label: l.label.trim(), url: l.url.trim() }))
      .filter((l) => l.url),
    tags: form.tags,
  }
  submitting.value = true
  try {
    await api.post('/galgames', payload)
    ElMessage.success('添加成功')
    router.push('/galgame')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '添加失败'))
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  tagCategories.value = await fetchTagStructure()
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
.add-links {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.add-link-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.add-link-label {
  width: 140px;
  flex-shrink: 0;
}
.add-link-url {
  flex: 1;
  min-width: 0;
}
.add-link-btn {
  align-self: flex-start;
}
</style>
