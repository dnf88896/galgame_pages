<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑投票' : '创建投票'"
    width="90%"
    style="max-width: 560px"
    :close-on-click-modal="false"
  >
    <div class="poll-form">
      <label class="form-label">标题 <span class="required">*</span></label>
      <el-input
        v-model="form.title"
        maxlength="100"
        show-word-limit
        placeholder="投票标题，如「最喜欢哪部作品？」"
      />

      <label class="form-label">描述（可选）</label>
      <el-input
        v-model="form.description"
        type="textarea"
        :rows="2"
        maxlength="500"
        show-word-limit
        placeholder="补充说明投票规则或背景"
      />

      <label class="form-label">投票类型</label>
      <el-radio-group v-model="form.type">
        <el-radio-button value="single">单选</el-radio-button>
        <el-radio-button value="multiple">多选</el-radio-button>
      </el-radio-group>
      <div v-if="form.type === 'multiple'" class="choice-range">
        <el-input-number v-model="form.min_choice" :min="1" :max="20" size="small" controls-position="right" />
        <span class="range-sep">至</span>
        <el-input-number v-model="form.max_choice" :min="1" :max="20" size="small" controls-position="right" />
        <span class="range-hint">项</span>
      </div>

      <label class="form-label">截止时间（可选）</label>
      <el-date-picker
        v-model="form.deadline"
        type="datetime"
        placeholder="不设截止则长期开放"
        style="width: 100%"
        value-format=""
      />

      <label class="form-label">结果可见性</label>
      <el-radio-group v-model="form.result_visibility" class="visibility-group">
        <el-radio value="always">所有人可见</el-radio>
        <el-radio value="after_vote">投票后可见</el-radio>
        <el-radio value="after_deadline">截止后可见</el-radio>
      </el-radio-group>

      <div class="form-switches">
        <el-checkbox v-model="form.is_anonymous">匿名投票</el-checkbox>
        <el-checkbox v-model="form.can_change_vote">允许修改投票</el-checkbox>
      </div>

      <label class="form-label">选项 <span class="required">*</span></label>
      <div class="option-rows">
        <div v-for="(opt, i) in form.options" :key="opt._key" class="option-row">
          <el-input
            v-model="opt.text"
            :placeholder="`选项 ${i + 1}`"
            maxlength="100"
            show-word-limit
            :disabled="opt._locked"
          />
          <el-button link type="danger" :disabled="opt._locked" @click="removeOption(i)">删除</el-button>
        </div>
      </div>
      <el-button type="primary" plain size="small" :disabled="optionsLocked" @click="addOption">添加选项</el-button>
      <p v-if="optionsLocked" class="option-lock-hint">该投票不允许修改选项，只能修改标题、类型等设置。</p>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { getErrorMessage } from '../utils/format'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  postId: { type: Number, required: true },
  poll: { type: Object, default: null }, // 编辑时传入,创建时 null
})

const emit = defineEmits(['update:modelValue', 'saved'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const isEdit = computed(() => !!props.poll)

let keySeq = 0
function newOpt(id, text) {
  return { id: id ?? undefined, text: text || '', _new: id == null, _locked: false, _key: ++keySeq }
}

const form = reactive({
  title: '',
  description: '',
  type: 'single',
  min_choice: 1,
  max_choice: 1,
  deadline: null, // Date | null
  result_visibility: 'always',
  is_anonymous: false,
  can_change_vote: true,
  options: [],
})

// 编辑模式下记录原始选项文本,用于 diff 出 update 列表
const origText = new Map()

function init() {
  if (isEdit.value) {
    const p = props.poll
    form.title = p.title || ''
    form.description = p.description || ''
    form.type = p.type === 'multiple' ? 'multiple' : 'single'
    form.min_choice = p.min_choice ?? 1
    form.max_choice = p.max_choice ?? 1
    form.deadline = p.deadline ? new Date(p.deadline) : null
    form.result_visibility = p.result_visibility || 'always'
    form.is_anonymous = !!p.is_anonymous
    form.can_change_vote = p.can_change_vote !== false
    form.options = (p.options || []).map((o) => {
      const opt = newOpt(o.id, o.text)
      // 选项已有票则锁定(不能改文本/删除);can_change_vote=false 时整个选项区只读
      opt._locked = p.can_change_vote === false || (o.vote_count || 0) > 0
      return opt
    })
  } else {
    form.title = ''
    form.description = ''
    form.type = 'single'
    form.min_choice = 1
    form.max_choice = 1
    form.deadline = null
    form.result_visibility = 'always'
    form.is_anonymous = false
    form.can_change_vote = true
    form.options = [newOpt(undefined, ''), newOpt(undefined, '')]
  }
  origText.clear()
  form.options.forEach((o) => {
    if (o.id != null) origText.set(o.id, o.text)
  })
}

watch(
  () => props.modelValue,
  (v) => {
    if (v) init()
  },
)

const saving = ref(false)

// 该投票是否整体锁定选项区(仅编辑且 can_change_vote=false 时)
const optionsLocked = computed(() => isEdit.value && props.poll?.can_change_vote === false)

function addOption() {
  if (form.options.length >= 20) {
    ElMessage.warning('选项最多 20 个')
    return
  }
  form.options.push(newOpt(undefined, ''))
}

function removeOption(i) {
  if (form.options.length <= 2) {
    ElMessage.warning('至少需要 2 个选项')
    return
  }
  form.options.splice(i, 1)
}

// 标量字段(创建/编辑共用);deadline 为空则省略该字段(后端契约)
function scalars() {
  const body = {
    title: form.title.trim(),
    description: form.description.trim(),
    type: form.type,
    min_choice: form.type === 'multiple' ? form.min_choice : 1,
    max_choice: form.type === 'multiple' ? form.max_choice : 1,
    deadline: fmtDeadline(form.deadline),
    result_visibility: form.result_visibility,
    is_anonymous: form.is_anonymous,
    can_change_vote: form.can_change_vote,
  }
  if (body.deadline == null) delete body.deadline
  return body
}

// 编辑模式:只发变化的选项
function diffOptions() {
  const add = form.options
    .filter((o) => o._new && o.text.trim())
    .map((o) => ({ text: o.text.trim() }))
  const update = form.options
    .filter((o) => !o._new && o.text.trim() !== (origText.get(o.id) || ''))
    .map((o) => ({ option_id: o.id, text: o.text.trim() }))
  const del = [...origText.keys()].filter((id) => !form.options.some((o) => o.id === id))
  return { add, update, delete: del }
}

function validate() {
  if (!form.title.trim()) return '请填写投票标题'
  if (form.options.length < 2) return '至少需要 2 个选项'
  if (form.options.length > 20) return '选项最多 20 个'
  if (form.options.some((o) => !o.text.trim())) return '选项内容不能为空'
  if (form.type === 'multiple') {
    if (form.min_choice < 1) return '至少选择数不能小于 1'
    if (form.max_choice < form.min_choice) return '至多选择数不能小于至少选择数'
    if (form.max_choice > form.options.length) return '至多选择数不能超过选项数'
  }
  if (form.deadline && form.deadline.getTime() < Date.now()) return '截止时间不能早于当前时间'
  return ''
}

// Date → "YYYY-MM-DDTHH:mm:ss"(ISO 局部时间,后端 LocalDateTime.parse 直接解析)
function fmtDeadline(d) {
  if (!d) return null
  const pad = (n) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

async function submit() {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  saving.value = true
  try {
    if (isEdit.value) {
      const body = { ...scalars(), options: diffOptions() }
      await api.put(`/polls/${props.poll.id}`, body)
      ElMessage.success('投票已更新')
    } else {
      const body = {
        ...scalars(),
        options: form.options.map((o) => ({ text: o.text.trim() })),
      }
      await api.post(`/posts/${props.postId}/polls`, body)
      ElMessage.success('投票已创建')
    }
    emit('saved')
    visible.value = false
  } catch (e) {
    ElMessage.error(getErrorMessage(e, isEdit.value ? '编辑投票失败' : '创建投票失败'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.poll-form {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.form-label {
  margin-top: 12px;
  font-size: 13px;
  color: #606266;
}
.form-label:first-child {
  margin-top: 0;
}
.required {
  color: #f56c6c;
}
.choice-range {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
.range-sep {
  color: #909399;
}
.range-hint {
  font-size: 13px;
  color: #909399;
}
.visibility-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}
.form-switches {
  display: flex;
  gap: 24px;
  margin-top: 12px;
}
.option-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 8px;
}
.option-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.option-lock-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #909399;
}
</style>
