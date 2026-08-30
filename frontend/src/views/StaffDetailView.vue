<template>
  <div class="page">
    <div class="gal-detail-center">
      <!-- 加载中骨架屏 -->
      <div v-if="loading" class="gal-detail-skel">
        <el-skeleton :rows="8" animated />
      </div>

      <!-- 404：制作人员不存在 -->
      <div v-else-if="notFound" class="gal-detail-empty">
        <p class="gal-detail-empty-text">制作人员不存在</p>
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
            <div v-if="canEdit" class="gal-detail-actions">
              <el-button v-if="isAdmin && isPending" type="success" plain size="small" @click="approve">通过审核</el-button>
              <el-button v-if="isAdmin && isPending" type="warning" plain size="small" @click="reject">拒绝</el-button>
              <el-button type="primary" plain size="small" @click="enterEdit">编辑</el-button>
              <el-button v-if="canDelete" type="danger" plain size="small" @click="remove">删除</el-button>
            </div>
          </div>

          <div class="gal-detail-card">
            <div class="gal-detail-cover">
              <img
                v-if="detail.image && !coverError"
                :src="resolveAssetUrl(detail.image)"
                :alt="detail.name"
                @error="coverError = true"
              />
              <div v-else class="gal-detail-cover-placeholder">制作人员</div>
            </div>
            <div class="gal-detail-body">
              <div class="gal-detail-name-row">
                <h1 class="gal-detail-name">{{ detail.name }}</h1>
                <el-tag v-if="detail.status === 'pending'" type="warning" size="small">待审核</el-tag>
                <el-tag v-else-if="detail.status === 'rejected'" type="danger" size="small">已拒绝</el-tag>
              </div>
              <el-alert
                v-if="detail.status === 'rejected' && detail.reject_reason"
                :title="`拒绝理由：${detail.reject_reason}`"
                type="error"
                :closable="false"
                class="gal-reject-reason"
              />
              <p v-if="detail.description" class="gal-detail-desc">{{ detail.description }}</p>
              <div class="gal-detail-meta">
                <span v-if="detail.creator">提交人：{{ detail.creator }}</span>
                <span>浏览 {{ detail.view_count || 0 }}</span>
                <span v-if="detail.created_at">创建：{{ formatTime(detail.created_at) }}</span>
                <span v-if="detail.updated_at">更新：{{ formatTime(detail.updated_at) }}</span>
              </div>
              <div class="gal-contributors-link" @click="goContributors">条目贡献者</div>
            </div>
          </div>

          <!-- 关联角色：该制作人员关联的已上架角色（详情接口返回 characters: [{id,name}]） -->
          <div class="works-section">
            <h2 class="works-title">关联角色</h2>
            <div v-if="characters.length" class="relation-list">
              <a
                v-for="c in characters"
                :key="c.id"
                class="gal-staff-link relation-item"
                @click="goCharacter(c.id)"
              >{{ c.name }}</a>
            </div>
            <div v-else class="works-empty">
              <p class="works-empty-text">暂无关联角色</p>
            </div>
          </div>

          <!-- 作品列表：该制作人员已上架的 galgame（GET /api/galgames?staff_id=X） -->
          <div class="works-section">
            <h2 class="works-title">作品</h2>

            <!-- 加载中骨架屏 -->
            <div v-if="worksLoading" class="works-list">
              <div v-for="i in 3" :key="i" class="works-skel-card">
                <el-skeleton :rows="3" animated />
              </div>
            </div>

            <!-- 加载失败 -->
            <el-alert
              v-else-if="worksError"
              :title="worksError"
              type="error"
              :closable="false"
              class="works-error"
            />

            <!-- 列表 / 空状态 -->
            <template v-else>
              <div v-if="works.length" class="works-list">
                <article v-for="w in works" :key="w.id" class="gal-card" @click="goWork(w.id)">
                  <div class="gal-cover">
                    <img
                      v-if="w.image && !w._coverError"
                      :src="resolveAssetUrl(w.image)"
                      :alt="w.name"
                      loading="lazy"
                      @error="workCoverError(w)"
                    />
                    <div v-else class="gal-cover-placeholder">作品</div>
                  </div>
                  <div class="gal-card-body">
                    <div class="gal-card-head">
                      <h3 class="gal-name">{{ w.name }}</h3>
                    </div>
                    <p v-if="w.description" class="gal-desc">{{ w.description }}</p>
                    <div class="gal-views">
                      <span>浏览 {{ w.view_count || 0 }}</span>
                      <span v-if="w.rating_avg != null">评分 {{ Number(w.rating_avg).toFixed(2) }} / 10</span>
                      <span v-else>暂无评分</span>
                    </div>
                  </div>
                </article>
              </div>
              <div v-else class="works-empty">
                <p class="works-empty-text">暂无关联作品</p>
              </div>
            </template>
          </div>
        </template>

        <!-- 编辑模式（字段与 AddStaffView 一致） -->
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
import { refreshMoe } from '../utils/moeGain'
import { getErrorMessage, resolveAssetUrl, formatTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const staffId = route.params.id

// 管理员（admin_level > 0）才显示编辑/删除/审核；等级来自 store，认证后实时刷新
const isAdmin = computed(() => Number(user.value?.admin_level) > 0)

// 是否本条目的创建者（后端 staffs.created_by，数字 id）
const isCreator = computed(() => detail.value?.created_by != null && detail.value.created_by === Number(user.value?.id))

// 是否待审核状态
const isPending = computed(() => detail.value?.status === 'pending')

// 可编辑（编辑申请入口）：管理员，或创建者本人——创建者不管条目状态都能进入编辑，提交已上架条目的修改会生成修改申请待审核
const canEdit = computed(() => isAdmin.value || isCreator.value || (user.value?.id != null && detail.value?.status === 'approved'))

// 可删除：管理员任意删，但 pending 审核中不显示删除（用通过/拒绝处置，避免审核界面出现删除）；
// 普通创建者仅 pending 可删自己提交
const canDelete = computed(() =>
  (isAdmin.value && detail.value?.status !== 'pending') ||
  (isCreator.value && detail.value?.status === 'pending'))

const detail = ref(null)
const loading = ref(false)
const notFound = ref(false)
const loadError = ref('')
const editMode = ref(false)

// 关联角色：详情接口返回 characters: [{id,name}]（snake_case 键）
const characters = computed(() => (Array.isArray(detail.value?.characters) ? detail.value.characters : []))

// 作品列表：GET /api/galgames?staff_id=X 返回该制作人员已上架的 galgame（snake_case 键）
const works = ref([])
const worksLoading = ref(false)
const worksError = ref('')

// 关联角色下拉选项：GET /characters 返回数组（不是 {data:...} 包一层），失败兜底空数组不影响编辑
const characterOptions = ref([])
async function loadCharacterOptions() {
  try {
    const { data } = await api.get('/characters')
    characterOptions.value = Array.isArray(data) ? data : []
  } catch (e) {
    // 拉取失败不阻断编辑（关联角色可空）
  }
}

// 编辑表单（与 AddStaffView 一致）
const form = reactive({
  name: '',
  description: '',
  image: '',
  characterIds: [],
})
const imagePreview = ref('')
const coverError = ref(false)
const submitting = ref(false)

async function loadDetail() {
  loading.value = true
  notFound.value = false
  loadError.value = ''
  coverError.value = false
  try {
    const { data } = await api.get(`/staffs/${staffId}`)
    detail.value = data
  } catch (e) {
    if (e?.response?.status === 404) {
      notFound.value = true
    } else {
      loadError.value = getErrorMessage(e, '加载失败')
    }
  } finally {
    loading.value = false
    // 详情加载成功后才拉作品列表；404 / 加载失败时 detail 为 null，不触发
    if (detail.value) {
      loadWorks()
    }
  }
}

// 作品列表：GET /api/galgames?staff_id=X 返回该制作人员已上架的 galgame 数组
async function loadWorks() {
  worksLoading.value = true
  worksError.value = ''
  try {
    const { data } = await api.get('/galgames', {
      params: { staff_id: staffId },
    })
    works.value = Array.isArray(data) ? data : []
  } catch (e) {
    worksError.value = getErrorMessage(e, '加载失败')
  } finally {
    worksLoading.value = false
  }
}

// 点击关联角色 → 进入角色详情页
function goCharacter(id) {
  if (id == null) return
  router.push('/character/' + id)
}

// 点击作品卡片进入对应 galgame 详情页
function goWork(id) {
  router.push(`/galgame/${id}`)
}

// 作品封面加载失败时回退到占位块
function workCoverError(w) {
  w._coverError = true
}

// 详情页「条目贡献者」小字 → 跳转贡献者列表页
function goContributors() {
  router.push('/staff/' + detail.value.id + '/contributors')
}

// 返回按钮：后退栈空（如直接输 URL 进入）时回列表页
function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/staff')
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

// 进入编辑模式：用已加载详情数据回填表单（关联角色回填 id 数组）
function enterEdit() {
  const d = detail.value || {}
  form.name = d.name || ''
  form.description = d.description || ''
  form.image = d.image || ''
  imagePreview.value = d.image ? resolveAssetUrl(d.image) : ''
  form.characterIds = Array.isArray(d.characters) ? d.characters.map((c) => c.id) : []
  editMode.value = true
}

// 取消编辑：还原展示模式（不保存）
function cancelEdit() {
  editMode.value = false
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
    await api.put(`/staffs/${staffId}`, payload)
    // 保存成功提示分场景：管理员原地保存；创建者改已上架条目 = 后端创建影子修改申请
    if (!isAdmin.value && detail.value?.status === 'approved') {
      ElMessage.success('修改申请已提交，等待管理员审核')
    } else {
      ElMessage.success('保存成功')
    }
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
    await ElMessageBox.confirm('确定删除该制作人员吗？删除后不可恢复。', '删除确认', { type: 'warning' })
  } catch {
    return // 用户取消，什么都不做
  }
  try {
    await api.delete(`/staffs/${staffId}`)
    ElMessage.success('删除成功')
    router.push('/staff')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '删除失败'))
  }
}

// 通过审核：POST /staffs/{id}/review { status: 'approved' }，成功后刷新详情。
// 提交者恰为当前用户（管理员审核自己的提交）时 +10，refreshMoe 检测增量弹「+n萌点」；审核他人无变化不弹。
async function approve() {
  try {
    await api.post(`/staffs/${staffId}/review`, { status: 'approved' })
    ElMessage.success('已通过审核')
    refreshMoe()
    await loadDetail()
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '操作失败'))
  }
}

// 拒绝：弹框填理由 → POST /staffs/{id}/review { status: 'rejected', reason }，成功后刷新详情
async function reject() {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt('请填写拒绝理由', '拒绝', {
      type: 'warning',
      inputPlaceholder: '拒绝理由',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && String(v).trim() ? true : '请填写拒绝理由。'),
    })
    reason = (value || '').trim()
  } catch {
    return // 用户取消，什么都不做
  }
  if (!reason) {
    ElMessage.warning('请填写拒绝理由。')
    return
  }
  try {
    await api.post(`/staffs/${staffId}/review`, { status: 'rejected', reason })
    ElMessage.success('已拒绝')
    await loadDetail()
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '操作失败'))
  }
}

onMounted(() => {
  loadDetail()
  loadCharacterOptions()
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
  align-self: flex-start;
  width: 240px;
  max-width: 100%;
  border-radius: 6px;
  overflow: hidden;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
}
.gal-detail-cover img {
  width: 100%;
  height: auto;
  display: block;
}
.gal-detail-cover-placeholder {
  width: 100%;
  height: 240px;
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
.gal-detail-name-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 8px;
}
.gal-detail-name-row .gal-detail-name {
  margin: 0;
}
.gal-detail-name {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
  margin: 0 0 8px;
}
.gal-reject-reason {
  margin-bottom: 12px;
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
/* 关联角色链接（复用 gal-staff-link 蓝色链接样式） */
.relation-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.relation-item {
  font-size: 15px;
}
/* 编辑模式（与 AddStaffView 一致） */
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
/* 作品列表（复用 GalgameView 的 .gal-card 风格，保持视觉一致） */
.works-section {
  margin-top: 24px;
}
.works-title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 16px;
}
.works-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.works-skel-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px;
}
.gal-card {
  display: flex;
  gap: 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.gal-card:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.2);
}
.gal-cover {
  flex-shrink: 0;
  width: 120px;
  height: 150px;
  border-radius: 6px;
  overflow: hidden;
  background: #f5f7fa;
}
.gal-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.gal-cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 13px;
  background: #f0f2f5;
}
.gal-card-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.gal-card-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.gal-name {
  font-size: 17px;
  font-weight: 700;
  color: #303133;
  margin: 0;
}
.gal-desc {
  margin: 8px 0 0;
  color: #666;
  font-size: 14px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.gal-views {
  display: flex;
  gap: 16px;
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
}
.works-error {
  margin-bottom: 16px;
}
.works-empty {
  text-align: center;
  padding: 32px 0;
  background: transparent;
  border: none;
}
.works-empty-text {
  font-size: 16px;
  font-weight: 600;
  color: #909399;
  margin: 0;
}
</style>
