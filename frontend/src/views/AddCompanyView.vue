<template>
  <div class="page">
    <div class="add-center">
      <div class="add-head">
        <el-button link type="primary" @click="goBack">← 返回</el-button>
        <h1 class="add-title">提交会社信息</h1>
      </div>

      <!-- 权限守卫：未登录只显示提示，不渲染表单 -->
      <div v-if="!loggedIn" class="add-denied">
        <p>请先登录后提交会社信息。</p>
        <el-button type="primary" @click="goLogin">去登录</el-button>
      </div>

      <div v-else class="add-card">
        <el-form :model="form" label-width="100px" class="add-form">
          <el-form-item label="名称" required>
            <el-input
              v-model="form.name"
              placeholder="会社名称（如 Key / Visual Art's）"
              maxlength="200"
              show-word-limit
            />
          </el-form-item>

          <el-form-item label="简介">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="5"
              placeholder="这家会社做什么的、代表作品…"
              maxlength="2000"
              show-word-limit
            />
          </el-form-item>

          <el-form-item label="官网">
            <el-input
              v-model="form.website"
              placeholder="https://…"
              maxlength="500"
            />
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
import { ref, reactive, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { user } from '../store/user'
import { refreshMoe } from '../utils/moeGain'
import { getErrorMessage } from '../utils/format'

const route = useRoute()
const router = useRouter()

// 登录用户（有 id）才能提交会社信息；等级来自 store，认证后实时刷新
const isAdmin = computed(() => Number(user.value?.admin_level) > 0)
const loggedIn = computed(() => !!user.value?.id)

// 未登录：跳登录页，登录成功后回跳当前提交页
function goLogin() {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

const form = reactive({
  name: '',
  description: '',
  website: '',
})
const submitting = ref(false)

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/company')
}

async function submit() {
  const name = form.name.trim()
  if (!name) {
    ElMessage.error('请填写会社名称。')
    return
  }
  const payload = {
    name,
    description: form.description.trim(),
    website: form.website.trim(),
  }
  submitting.value = true
  try {
    await api.post('/companies', payload)
    if (isAdmin.value) {
      ElMessage.success('添加成功')
      // 管理员创建即上架 → 提交者（自己）+10 萌点；refreshMoe 通用检测提示并同步 store/基线
      refreshMoe()
    } else {
      ElMessage.success('已提交，等待管理员审核')
    }
    router.push('/company')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '提交失败'))
  } finally {
    submitting.value = false
  }
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
</style>
