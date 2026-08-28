<template>
  <transition name="moe-fade">
    <div v-if="visible" :key="current.key" class="moe-toast">+{{ current.amount }}萌点</div>
  </transition>
</template>

<script setup>
import { ref, watch } from 'vue'
import { moeGain } from '../utils/moeGain'

// 屏幕中心短提示：显示「+n萌点」后慢慢上移淡出消失
const visible = ref(false)
const current = ref({ amount: 0, key: 0 })
let timer = null

watch(moeGain, (v) => {
  if (!v) return
  current.value = v
  visible.value = true
  clearTimeout(timer)
  timer = setTimeout(() => {
    visible.value = false
  }, 2000)
})
</script>

<style scoped>
.moe-toast {
  position: fixed;
  top: 45%;
  left: 50%;
  transform: translate(-50%, -50%);
  z-index: 9999;
  font-size: 30px;
  font-weight: 700;
  color: #ff7d5c;
  letter-spacing: 2px;
  text-shadow: 0 2px 12px rgba(255, 125, 92, 0.35);
  pointer-events: none;
  user-select: none;
  animation: moe-float 2s ease forwards;
}
/* 浮现 → 短暂停留 → 缓慢上移并淡出 */
@keyframes moe-float {
  0% {
    opacity: 0;
    transform: translate(-50%, -50%) scale(0.7);
  }
  15% {
    opacity: 1;
    transform: translate(-50%, -50%) scale(1.1);
  }
  30% {
    transform: translate(-50%, -50%) scale(1);
  }
  55% {
    opacity: 1;
    transform: translate(-50%, -58%);
  }
  100% {
    opacity: 0;
    transform: translate(-50%, -72%);
  }
}
</style>
