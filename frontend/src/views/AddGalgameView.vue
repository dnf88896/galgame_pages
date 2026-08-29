<template>
  <div class="page">
    <div class="add-center">
      <div class="add-head">
        <el-button link type="primary" @click="goBack">← 返回</el-button>
        <h1 class="add-title">提交 Galgame 信息</h1>
      </div>

      <!-- 权限守卫：未登录只显示提示，不渲染表单 -->
      <div v-if="!loggedIn" class="add-denied">
        <p>请先登录后提交 Galgame 信息。</p>
        <el-button type="primary" @click="goLogin">去登录</el-button>
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

          <el-form-item label="分类">
            <el-select v-model="form.categories" multiple collapse-tags filterable placeholder="选择分类" style="width:100%">
              <el-option-group v-for="g in CATEGORY_GROUPS" :key="g.key" :label="g.name">
                <el-option v-for="c in GALGAME_CATEGORIES.filter(x => x.group === g.key)" :key="c.key" :value="c.key" :label="c.label" />
              </el-option-group>
            </el-select>
          </el-form-item>

          <el-form-item label="标签" required>
            <GalgameTagSelect v-model="form.tagIds" />
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

          <el-form-item label="会社">
            <el-select
              v-model="form.companyId"
              clearable
              filterable
              placeholder="选择会社（可不选）"
              class="add-tags-select"
            >
              <el-option v-for="c in companyOptions" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>

          <el-form-item label="制作人员">
            <div class="add-links">
              <div v-for="(link, i) in form.staffLinks" :key="i" class="add-link-row">
                <el-input
                  v-model="link.description"
                  placeholder="职责/备注（如 脚本、原画）"
                  class="add-link-label"
                  maxlength="200"
                />
                <el-select
                  v-model="link.id"
                  filterable
                  clearable
                  placeholder="选择制作人员"
                  class="add-link-url"
                >
                  <el-option v-for="s in staffOptions" :key="s.id" :label="s.name" :value="s.id" />
                </el-select>
                <el-button
                  link
                  type="danger"
                  :aria-label="`删除制作人员 ${i + 1}`"
                  @click="form.staffLinks.splice(i, 1)"
                >删除</el-button>
              </div>
              <el-button type="primary" plain size="small" class="add-link-btn" @click="form.staffLinks.push({ id: null, description: '' })">+ 添加制作人员</el-button>
            </div>
          </el-form-item>

          <el-form-item label="角色">
            <div class="add-links">
              <div v-for="(link, i) in form.characterLinks" :key="i" class="add-link-row">
                <el-input
                  v-model="link.description"
                  placeholder="定位/备注（如 女主、CV）"
                  class="add-link-label"
                  maxlength="200"
                />
                <el-select
                  v-model="link.id"
                  filterable
                  clearable
                  placeholder="选择角色"
                  class="add-link-url"
                >
                  <el-option v-for="c in characterOptions" :key="c.id" :label="c.name" :value="c.id" />
                </el-select>
                <el-button
                  link
                  type="danger"
                  :aria-label="`删除角色 ${i + 1}`"
                  @click="form.characterLinks.splice(i, 1)"
                >删除</el-button>
              </div>
              <el-button type="primary" plain size="small" class="add-link-btn" @click="form.characterLinks.push({ id: null, description: '' })">+ 添加角色</el-button>
            </div>
          </el-form-item>

          <el-form-item label="发售日期">
            <el-date-picker
              v-model="form.releaseDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择发售日期"
              class="add-date-picker"
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
import GalgameTagSelect from '../components/GalgameTagSelect.vue'
import { GALGAME_CATEGORIES, CATEGORY_GROUPS } from '../constants/galgameCategory'

const route = useRoute()
const router = useRouter()

// 登录用户（有 id）才能提交 galgame 信息；等级来自 store，认证后实时刷新
const isAdmin = computed(() => Number(user.value?.admin_level) > 0)
const loggedIn = computed(() => !!user.value?.id)

// 未登录：跳登录页，登录成功后回跳当前提交页
function goLogin() {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

const form = reactive({
  name: '',
  categories: [],
  tagIds: [],
  image: '',
  description: '',
  companyId: null,
  staffLinks: [],
  characterLinks: [],
  releaseDate: '',
})
const links = ref([{ label: '', url: '' }])
const imagePreview = ref('')
const submitting = ref(false)

// 会社下拉选项：GET /companies 返回数组（不是 {data:...} 包一层），失败兜底空数组不影响提交
const companyOptions = ref([])
async function loadCompanyOptions() {
  try {
    const { data } = await api.get('/companies')
    companyOptions.value = Array.isArray(data) ? data : []
  } catch (e) {
    // 拉取失败不阻断提交（会社可空）
  }
}

// 制作人员下拉选项：GET /staffs 返回数组（仅 approved），失败兜底空数组不影响提交
const staffOptions = ref([])
async function loadStaffOptions() {
  try {
    const { data } = await api.get('/staffs')
    staffOptions.value = Array.isArray(data) ? data : []
  } catch (e) {
    // 拉取失败不阻断提交（制作人员可空）
  }
}

// 角色下拉选项：GET /characters 返回数组（仅 approved），失败兜底空数组不影响提交
const characterOptions = ref([])
async function loadCharacterOptions() {
  try {
    const { data } = await api.get('/characters')
    characterOptions.value = Array.isArray(data) ? data : []
  } catch (e) {
    // 拉取失败不阻断提交（角色可空）
  }
}

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
  if (!form.tagIds.length) {
    ElMessage.error('请至少选择 1 个标签。')
    return
  }
  const payload = {
    name,
    categories: form.categories.filter((c) => c && c.trim()),
    description: form.description.trim(),
    image: form.image,
    company_id: form.companyId || null,
    staffs: form.staffLinks.filter((l) => l.id != null).map((l) => ({ id: l.id, description: (l.description || '').trim() })),
    characters: form.characterLinks.filter((l) => l.id != null).map((l) => ({ id: l.id, description: (l.description || '').trim() })),
    release_date: form.releaseDate || null,
    links: links.value
      .map((l) => ({ label: l.label.trim(), url: l.url.trim() }))
      .filter((l) => l.url),
    tag_ids: form.tagIds.filter((id) => id != null),
  }
  submitting.value = true
  try {
    await api.post('/galgames', payload)
    if (isAdmin.value) {
      ElMessage.success('添加成功')
      // 管理员创建即上架 → 提交者（自己）+10 萌点；refreshMoe 通用检测提示并同步 store/基线
      refreshMoe()
    } else {
      ElMessage.success('已提交，等待管理员审核')
    }
    router.push('/galgame')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '提交失败'))
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadCompanyOptions()
  loadStaffOptions()
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
.add-date-picker {
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
