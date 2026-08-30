<template>
  <div class="page">
    <div class="add-center">
      <div class="add-head">
        <el-button link type="primary" @click="goBack">← 返回</el-button>
        <h1 class="add-title">{{ title }}</h1>
        <span v-if="validCount" class="gal-links-count">已选 {{ validCount }} 名</span>
      </div>

      <div class="add-card">
        <div class="add-links">
          <div v-for="(link, i) in links" :key="i" class="add-link-row">
            <el-input
              v-model="link.description"
              :placeholder="descPlaceholder"
              class="add-link-label"
              maxlength="200"
            />
            <el-select
              v-model="link.id"
              filterable
              clearable
              :placeholder="selectPlaceholder"
              class="add-link-url"
            >
              <el-option v-for="s in options" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
            <el-button
              link
              type="danger"
              :aria-label="`删除${typeLabel} ${i + 1}`"
              @click="links.splice(i, 1)"
            >删除</el-button>
          </div>

          <!-- 制作人员额外：批量行（一个职业 + 多选多人 → + 添加） -->
          <div v-if="isStaffs" class="add-link-row">
            <el-input v-model="batchRole" placeholder="职业/职责（如 脚本、原画）" class="add-link-label" maxlength="200" />
            <el-select v-model="batchIds" multiple filterable clearable placeholder="选择多名制作人员" class="add-link-url">
              <el-option v-for="s in options" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
            <el-button type="primary" plain size="small" @click="addBatch">+ 添加</el-button>
          </div>

          <!-- 角色：行式添加入口 -->
          <el-button v-if="!isStaffs" type="primary" plain size="small" class="add-link-btn" @click="addRow">+ 添加角色</el-button>
        </div>

        <div v-if="!links.length" class="gal-links-empty">
          尚未选择{{ typeLabel }}，从下方下拉选择后点「完成」。
        </div>

        <div class="gal-links-footer">
          <el-button type="primary" @click="done">完成</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import { loadDraft, saveDraft } from '../utils/galLinksDraft'

const props = defineProps({ type: { type: String, default: 'staffs' } })
const router = useRouter()

const isStaffs = computed(() => props.type === 'staffs')
const title = computed(() => (isStaffs.value ? '修改制作人员' : '修改角色'))
const typeLabel = computed(() => (isStaffs.value ? '制作人员' : '角色'))
const linksKey = computed(() => (isStaffs.value ? 'staffLinks' : 'characterLinks'))
const selectPlaceholder = computed(() => (isStaffs.value ? '选择制作人员' : '选择角色'))
const descPlaceholder = computed(() => (isStaffs.value ? '职责/备注（如 脚本、原画）' : '定位/备注（如 女主、CV）'))

// 下拉选项：制作人员→GET /staffs，角色→GET /characters（仅 approved），失败兜底空数组
const options = ref([])
async function loadOptions() {
  try {
    const { data } = await api.get(isStaffs.value ? '/staffs' : '/characters')
    options.value = Array.isArray(data) ? data : []
  } catch (e) {
    options.value = []
  }
}

// 本地编辑列表：从草稿主表单对应字段回填
const links = ref([])
onMounted(() => {
  const draft = loadDraft()
  const init = draft && draft.form && Array.isArray(draft.form[linksKey.value]) ? draft.form[linksKey.value] : []
  links.value = init.map((l) => ({ id: l.id ?? null, description: l.description || '' }))
  loadOptions()
})

const validCount = computed(() => links.value.filter((l) => l.id != null).length)

// 角色行式添加：追加一个空行（选人+定位后填）
function addRow() {
  links.value.push({ id: null, description: '' })
}

// 制作人员批量添加：一个职业 + 多人 → 展开多行（跳过同 (id,职责) 重复）
const batchRole = ref('')
const batchIds = ref([])
function addBatch() {
  const role = batchRole.value.trim()
  const ids = batchIds.value.filter((id) => id != null)
  if (!ids.length) return
  for (const id of ids) {
    if (links.value.some((l) => l.id === id && (l.description || '') === role)) continue
    links.value.push({ id, description: role })
  }
  batchRole.value = ''
  batchIds.value = []
}

// 完成：编辑结果写回草稿主表单对应字段 → 返回主表单恢复（未保存内容一并保留）
function done() {
  const draft = loadDraft()
  if (draft && draft.form) {
    draft.form[linksKey.value] = links.value.map((l) => ({ id: l.id ?? null, description: l.description || '' }))
    saveDraft(draft)
  }
  goBack()
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/galgame')
}
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
.gal-links-count {
  color: #909399;
  font-size: 13px;
}
.add-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 24px;
  margin-bottom: 16px;
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
.gal-links-empty {
  color: #909399;
  font-size: 13px;
  padding: 12px 0;
}
.gal-links-footer {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}
</style>
