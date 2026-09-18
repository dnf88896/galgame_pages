<template>
  <div class="gal-tag-select">
    <!-- 已选标签 + 打开选择弹窗 -->
    <div class="gal-tag-current">
      <template v-if="selectedTags.length">
        <el-tag
          v-for="t in selectedTags"
          :key="t.id"
          :type="CATEGORY_COLOR[t.category] || 'primary'"
          closable
          @close="removeTag(t.id)"
        >{{ t.name }}</el-tag>
      </template>
      <span v-else class="gal-tag-current-empty">尚未选择标签</span>
      <el-button size="small" type="primary" plain @click="openPicker">选择标签</el-button>
    </div>

    <!-- 选择弹窗：搜索 + 按类别分组的全部标签 -->
    <el-dialog
      v-model="pickerVisible"
      title="选择标签"
      width="90%"
      style="max-width: 640px"
      :close-on-click-modal="false"
    >
      <el-input
        v-model="keyword"
        placeholder="搜索标签（名称模糊匹配）…"
        clearable
        class="gal-tag-search-input"
      />
      <div v-loading="allLoading" class="gal-tag-picker-body">
        <div v-for="g in groupedTags" :key="g.category" class="gal-tag-group">
          <div class="gal-tag-group-head">
            <h4>{{ g.label }}</h4>
            <span class="gal-tag-group-count">{{ g.tags.length }}</span>
          </div>
          <div class="gal-tag-group-chips">
            <el-check-tag
              v-for="t in g.tags"
              :key="t.id"
              :checked="selectedSet.has(Number(t.id))"
              class="gal-tag-pick"
              @change="toggleTag(t.id)"
            >{{ t.name }}</el-check-tag>
          </div>
        </div>
        <div v-if="!allLoading && !filteredTags.length" class="gal-tag-picker-empty">
          <p class="gal-tag-picker-empty-text">没有匹配的标签</p>
          <el-button link type="primary" @click="openCreate">点击新建「{{ keyword }}」</el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="pickerVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmPicker">确定（已选 {{ selectedSet.size }} 个）</el-button>
      </template>
    </el-dialog>

    <!-- 新建标签弹窗：name + category(7 枚举) + 剧透级 + description -->
    <el-dialog
      v-model="dialogVisible"
      title="新建标签"
      width="90%"
      style="max-width: 440px"
      :close-on-click-modal="false"
      @closed="resetCreateForm"
    >
      <el-form :model="createForm" label-width="72px">
        <el-form-item label="名称" required>
          <el-input
            v-model="createForm.name"
            placeholder="标签名称（如：纯爱、废萌、NTR）"
            maxlength="50"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="类别" required>
          <el-select v-model="createForm.category" class="add-tags-select">
            <el-option
              v-for="opt in CATEGORY_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="剧透级">
          <el-radio-group v-model="createForm.spoiler_level">
            <el-radio :value="0">无剧透</el-radio>
            <el-radio :value="1">轻微剧透</el-radio>
            <el-radio :value="2">严重剧透</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="说明">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="可选"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { user } from '../store/user'
import {
  CATEGORY_LABELS,
  CATEGORY_OPTIONS,
  CATEGORY_COLOR,
  TAG_LIBRARY_CATEGORY_ORDER,
} from '../constants/galgameTag'

const props = defineProps({
  // 已选标签 id 数组（v-model）
  modelValue: { type: Array, default: () => [] },
  // 预置候选标签（如编辑回填时详情已有的标签对象数组 [{id,name,category,spoiler_level}]），用于展示已选名称
  preloadOptions: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue'])

const isAdmin = computed(() => Number(user.value?.admin_level) > 0)

// 全部 approved 标签（进入即拉全量，供分组浏览/搜索；搜索接口 limit 已放大到 1000）
const allTags = ref([])
const allLoading = ref(false)
// id → 标签对象，用于把已选 id 映射成名称展示
const tagMap = ref(new Map())
const keyword = ref('')

// 选择弹窗
const pickerVisible = ref(false)
const selectedSet = ref(new Set())

// 新建标签弹窗
const dialogVisible = ref(false)
const creating = ref(false)
const createForm = ref({ name: '', category: 'content', spoiler_level: 0, description: '' })

// 已选标签对象（按 modelValue 顺序，缺失 id 的跳过）
const selectedTags = computed(() =>
  (props.modelValue || []).map((v) => tagMap.value.get(Number(v))).filter(Boolean),
)

// 关键词过滤后的全部标签
const filteredTags = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return allTags.value
  return allTags.value.filter((t) => t.name.toLowerCase().includes(kw))
})

// 按 TAG_LIBRARY_CATEGORY_ORDER 分组展示（顺序固定，缺失类别置空跳过）
const groupedTags = computed(() => {
  const byCat = new Map()
  for (const t of filteredTags.value) {
    if (!byCat.has(t.category)) byCat.set(t.category, [])
    byCat.get(t.category).push(t)
  }
  return TAG_LIBRARY_CATEGORY_ORDER
    .map((c) => ({ category: c, label: CATEGORY_LABELS[c] || c, tags: byCat.get(c) || [] }))
    .filter((g) => g.tags.length)
})

async function loadAll() {
  allLoading.value = true
  try {
    const { data } = await api.get('/galgame-tags/search', { params: {} })
    allTags.value = Array.isArray(data) ? data : []
  } catch (e) {
    allTags.value = []
  } finally {
    allLoading.value = false
    rebuildMap()
  }
}

// 重建 id→对象 map：allTags + preloadOptions（编辑回填的已选标签可能不在全部列表里，如刚过审）
function rebuildMap() {
  const m = new Map(allTags.value.map((t) => [Number(t.id), t]))
  const list = Array.isArray(props.preloadOptions) ? props.preloadOptions : []
  list.forEach((o) => {
    if (o && o.id != null && !m.has(Number(o.id))) m.set(Number(o.id), o)
  })
  tagMap.value = m
}

watch(() => props.preloadOptions, rebuildMap, { immediate: true })

function openPicker() {
  // 同步当前已选到临时勾选态，并重置搜索
  selectedSet.value = new Set((props.modelValue || []).map(Number))
  keyword.value = ''
  pickerVisible.value = true
}

function toggleTag(id) {
  const n = Number(id)
  const s = new Set(selectedSet.value)
  if (s.has(n)) s.delete(n)
  else s.add(n)
  selectedSet.value = s
}

function removeTag(id) {
  const n = Number(id)
  emit('update:modelValue', (props.modelValue || []).filter((v) => Number(v) !== n))
}

function confirmPicker() {
  emit('update:modelValue', [...selectedSet.value])
  pickerVisible.value = false
}

function openCreate() {
  // 从选择弹窗点「新建」时，把当前关键词预填为名称（若有）
  if (keyword.value.trim() && !createForm.value.name) {
    createForm.value.name = keyword.value.trim()
  }
  dialogVisible.value = true
}

function resetCreateForm() {
  createForm.value = { name: '', category: 'content', spoiler_level: 0, description: '' }
}

// 提交新建：管理员即上架可直接选；普通用户提交后 pending，提示等待审核。
// 400「标签已存在」→ 提示去搜索选择。
async function submitCreate() {
  const name = createForm.value.name.trim()
  if (!name) {
    ElMessage.error('请填写标签名称。')
    return
  }
  creating.value = true
  try {
    const { data } = await api.post('/galgame-tags', {
      name,
      category: createForm.value.category,
      spoiler_level: createForm.value.spoiler_level,
      description: createForm.value.description.trim(),
    })
    const created = data || {}
    if (isAdmin.value && created.id != null) {
      // 管理员创建的标签即 approved：直接加入已选项 + 全部列表
      emit('update:modelValue', [...(props.modelValue || []), created.id])
      allTags.value = [created, ...allTags.value.filter((t) => t.id !== created.id)]
      rebuildMap()
      ElMessage.success('标签已创建')
    } else {
      ElMessage.success('标签已提交，待管理员审核后可用')
    }
    dialogVisible.value = false
  } catch (e) {
    const msg = e?.response?.data?.error || e?.message || '创建失败'
    if (msg.includes('标签已存在')) {
      ElMessage.warning('该标签已存在，请直接搜索选择。')
    } else {
      ElMessage.error(msg)
    }
  } finally {
    creating.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.gal-tag-select {
  width: 100%;
}
.gal-tag-current {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 36px;
}
.gal-tag-current-empty {
  color: #909399;
  font-size: 13px;
}
.gal-tag-search-input {
  margin-bottom: 12px;
}
.gal-tag-picker-body {
  /* 手机上限 50vh，保证弹窗页脚的「确定」按钮始终可见（不必滚动整屏去找） */
  max-height: min(420px, 50vh);
  overflow-y: auto;
  padding-right: 4px;
}
.gal-tag-group {
  margin-bottom: 16px;
}
.gal-tag-group-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.gal-tag-group-head h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}
.gal-tag-group-count {
  font-size: 12px;
  color: #909399;
}
.gal-tag-group-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.gal-tag-pick {
  margin: 0;
}
.gal-tag-picker-empty {
  text-align: center;
  padding: 32px 0;
}
.gal-tag-picker-empty-text {
  color: #909399;
  font-size: 14px;
  margin: 0 0 8px;
}
</style>
