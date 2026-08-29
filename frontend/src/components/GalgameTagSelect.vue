<template>
  <div class="gal-tag-select">
    <el-select
      :model-value="modelValue"
      multiple
      filterable
      remote
      reserve-keyword
      collapse-tags
      placeholder="搜索选择标签（至少 1 个）"
      class="add-tags-select"
      :remote-method="searchTags"
      :loading="searchLoading"
      @update:model-value="onUpdate"
    >
      <el-option
        v-for="t in options"
        :key="t.id"
        :label="`${t.name} · ${CATEGORY_LABELS[t.category] || t.category}`"
        :value="t.id"
      />
      <template #empty>
        <div class="tag-search-empty" @mousedown.stop.prevent @click="openCreate">
          <span>没有匹配标签，点击新建</span>
        </div>
      </template>
    </el-select>
    <el-button link type="primary" class="gal-tag-create-btn" @click="openCreate">+ 新建标签</el-button>

    <!-- 新建标签弹窗：name + category(7 枚举) + 剧透级 + description -->
    <el-dialog
      v-model="dialogVisible"
      title="新建标签"
      width="440px"
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
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { user } from '../store/user'
import { CATEGORY_LABELS, CATEGORY_OPTIONS } from '../constants/galgameTag'

const props = defineProps({
  // 已选标签 id 数组（v-model）
  modelValue: { type: Array, default: () => [] },
  // 预置候选标签（如编辑回填时详情已有的标签对象数组 [{id,name,category,spoiler_level}]），用于展示已选名称
  preloadOptions: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue'])

const isAdmin = computed(() => Number(user.value?.admin_level) > 0)

// 远程搜索候选：GET /api/galgame-tags/search?q=
const options = ref([])
const searchLoading = ref(false)

// 新建标签弹窗
const dialogVisible = ref(false)
const creating = ref(false)
const createForm = ref({ name: '', category: 'content', spoiler_level: 0, description: '' })

function onUpdate(val) {
  emit('update:modelValue', val)
}

async function searchTags(query) {
  searchLoading.value = true
  try {
    const { data } = await api.get('/galgame-tags/search', {
      params: { q: (query || '').trim() || undefined },
    })
    options.value = Array.isArray(data) ? data : []
  } catch (e) {
    options.value = []
  } finally {
    searchLoading.value = false
  }
}

// 预置候选合并进 options：保证已选标签（编辑回填）在下拉/选中态里显示名称
watch(
  () => props.preloadOptions,
  (list) => {
    const arr = Array.isArray(list) ? list : []
    if (!arr.length) return
    const map = new Map(options.value.map((o) => [o.id, o]))
    arr.forEach((o) => {
      if (o && o.id != null && !map.has(o.id)) map.set(o.id, o)
    })
    options.value = [...map.values()]
  },
  { immediate: true },
)

function openCreate() {
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
      // 管理员创建的标签即 approved：直接加入已选项 + 候选列表
      emit('update:modelValue', [...(props.modelValue || []), created.id])
      options.value = [created, ...options.value.filter((t) => t.id !== created.id)]
      ElMessage.success('标签已创建')
    } else {
      ElMessage.success('标签已提交，待管理员审核后可用')
    }
    dialogVisible.value = false
  } catch (e) {
    const msg = e?.response?.data?.error || e?.message || '创建失败'
    if (msg.includes('标签已存在')) {
      ElMessage.warning('该标签已存在，请直接在下拉中搜索选择。')
    } else {
      ElMessage.error(msg)
    }
  } finally {
    creating.value = false
  }
}

// 进入即预载全部候选（远程模式下点击下拉才有选项可显示）
searchTags('')
</script>

<style scoped>
.gal-tag-select {
  width: 100%;
}
.gal-tag-select .add-tags-select {
  width: 100%;
}
.gal-tag-create-btn {
  margin-top: 4px;
}
.tag-search-empty {
  padding: 10px 12px;
  color: #409eff;
  font-size: 13px;
  cursor: pointer;
  text-align: center;
  border-radius: 4px;
}
.tag-search-empty:hover {
  background: #f5f7fa;
}
</style>
