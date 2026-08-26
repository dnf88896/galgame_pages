<template>
  <div class="page">
    <div class="gal-detail-center">
      <!-- 加载中骨架屏 -->
      <div v-if="loading" class="gal-detail-skel">
        <el-skeleton :rows="8" animated />
      </div>

      <!-- 404：作品不存在 -->
      <div v-else-if="notFound" class="gal-detail-empty">
        <p class="gal-detail-empty-text">Galgame 不存在</p>
        <el-button type="primary" @click="goBack">返回列表</el-button>
      </div>

      <!-- 加载失败 -->
      <el-alert
        v-else-if="loadError"
        :title="loadError"
        type="error"
        :closable="false"
        class="gal-detail-error"
      />

      <!-- 详情（展示 / 编辑） -->
      <template v-else-if="detail">
        <!-- 展示模式（默认） -->
        <template v-if="!editMode">
          <div class="gal-detail-head">
            <el-button link type="primary" @click="goBack">← 返回</el-button>
            <div v-if="isAdmin" class="gal-detail-actions">
              <el-button type="primary" plain size="small" @click="enterEdit">编辑</el-button>
              <el-button type="danger" plain size="small" @click="remove">删除</el-button>
            </div>
          </div>

          <div class="gal-detail-card">
            <div class="gal-detail-cover">
              <img
                v-if="detail.image && !detail._coverError"
                :src="resolveAssetUrl(detail.image)"
                :alt="detail.name"
                @error="coverImgError"
              />
              <div v-else class="gal-detail-cover-placeholder">无封面</div>
            </div>
            <div class="gal-detail-body">
              <h1 class="gal-detail-name">{{ detail.name }}</h1>
              <div v-if="detail.tags && detail.tags.length" class="gal-tags">
                <span v-for="k in detail.tags" :key="k" class="gal-tag">#{{ sectionLabel(tagCategories, k) || k }}</span>
              </div>
              <p v-if="detail.staff" class="gal-detail-staff">{{ detail.staff }}</p>
              <p v-if="detail.description" class="gal-detail-desc">{{ detail.description }}</p>
              <div class="gal-links">
                <template v-if="detail.links && detail.links.length">
                  <button
                    v-for="(link, i) in detail.links"
                    :key="i"
                    class="gal-link"
                    @click="openLink(link.url)"
                  >{{ link.label || link.url }}</button>
                </template>
                <span v-else class="gal-no-link">暂无资源链接</span>
              </div>
              <div class="gal-detail-meta">
                <span v-if="detail.created_at">创建：{{ formatTime(detail.created_at) }}</span>
                <span v-if="detail.updated_at">更新：{{ formatTime(detail.updated_at) }}</span>
              </div>
            </div>
          </div>
        </template>

        <!-- 编辑模式（管理员，字段与 AddGalgameView 完全一致） -->
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
              <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
              <el-button @click="cancelEdit">取消</el-button>
            </el-form-item>
          </el-form>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { user } from '../store/user'
import { fetchTagStructure, sectionLabel, getErrorMessage, resolveAssetUrl, formatTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const galgameId = route.params.id

// 管理员（admin_level > 0）才显示编辑/删除；等级来自 store，认证后实时刷新
const isAdmin = computed(() => Number(user.value?.admin_level) > 0)

const detail = ref(null)
const loading = ref(false)
const notFound = ref(false)
const loadError = ref('')
const editMode = ref(false)

// 编辑表单（与 AddGalgameView 完全一致）
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

async function loadDetail() {
  loading.value = true
  notFound.value = false
  loadError.value = ''
  try {
    const { data } = await api.get(`/galgames/${galgameId}`)
    detail.value = data
  } catch (e) {
    if (e?.response?.status === 404) {
      notFound.value = true
    } else {
      loadError.value = getErrorMessage(e, '加载失败')
    }
  } finally {
    loading.value = false
  }
}

// 返回按钮：后退栈空（如直接输 URL 进入）时回列表页
function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/galgame')
}

// 进入编辑模式：用已加载详情数据回填表单
function enterEdit() {
  const d = detail.value || {}
  form.name = d.name || ''
  form.description = d.description || ''
  form.staff = d.staff || ''
  form.image = d.image || ''
  form.tags = Array.isArray(d.tags) ? [...d.tags] : []
  links.value = Array.isArray(d.links) && d.links.length
    ? d.links.map((l) => ({ label: l.label || '', url: l.url || '' }))
    : [{ label: '', url: '' }]
  imagePreview.value = d.image ? resolveAssetUrl(d.image) : ''
  editMode.value = true
}

// 取消编辑：还原展示模式（不保存）
function cancelEdit() {
  editMode.value = false
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
    await api.put(`/galgames/${galgameId}`, payload)
    ElMessage.success('保存成功')
    editMode.value = false
    await loadDetail()
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '保存失败'))
  } finally {
    submitting.value = false
  }
}

// 删除：二次确认后 DELETE，成功回列表
async function remove() {
  try {
    await ElMessageBox.confirm('确定删除该 Galgame 吗？删除后不可恢复。', '删除确认', { type: 'warning' })
  } catch {
    return // 用户取消，什么都不做
  }
  try {
    await api.delete(`/galgames/${galgameId}`)
    ElMessage.success('删除成功')
    router.push('/galgame')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '删除失败'))
  }
}

function openLink(url) {
  if (!url) return
  window.open(url, '_blank', 'noopener')
}

// 大图封面加载失败时回退到占位块
function coverImgError() {
  if (detail.value) detail.value._coverError = true
}

onMounted(async () => {
  tagCategories.value = await fetchTagStructure()
  loadDetail()
})
</script>

<style scoped>
.page {
  width: 100%;
}
.gal-detail-center {
  max-width: 900px;
  margin: 0 auto;
  padding: 24px 16px 0;
}
.gal-detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.gal-detail-actions {
  display: flex;
  gap: 8px;
}
.gal-detail-card {
  display: flex;
  gap: 24px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  margin-bottom: 16px;
}
.gal-detail-cover {
  flex-shrink: 0;
  width: 280px;
  height: 360px;
  border-radius: 6px;
  overflow: hidden;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
}
.gal-detail-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.gal-detail-cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 13px;
  background: #f0f2f5;
}
.gal-detail-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.gal-detail-name {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
  margin: 0 0 8px;
}
.gal-detail-staff {
  margin: 12px 0 0;
  color: #888;
  font-size: 14px;
}
.gal-detail-desc {
  margin: 12px 0 0;
  color: #666;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.gal-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 16px;
  color: #909399;
  font-size: 13px;
}
.gal-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.gal-tag {
  color: #409eff;
  background: #ecf5ff;
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 12px;
  line-height: 1.6;
}
.gal-links {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.gal-link {
  border: 1px solid #b3d8ff;
  background: #ecf5ff;
  color: #409eff;
  border-radius: 6px;
  padding: 3px 10px;
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
  transition: all 0.15s;
}
.gal-link:hover {
  background: #409eff;
  color: #fff;
}
.gal-no-link {
  color: #b0b3b8;
  font-size: 12px;
}
.gal-detail-skel {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 24px;
  margin-bottom: 16px;
}
.gal-detail-empty {
  text-align: center;
  padding: 64px 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  margin-bottom: 16px;
}
.gal-detail-empty-text {
  font-size: 18px;
  font-weight: 600;
  color: #909399;
  margin: 0 0 16px;
}
.gal-detail-error {
  margin-bottom: 16px;
}
/* 编辑模式（与 AddGalgameView 一致） */
.add-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 24px;
  margin-bottom: 16px;
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
