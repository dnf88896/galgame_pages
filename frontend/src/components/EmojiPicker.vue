<template>
  <div class="emoji-picker">
    <div
      v-for="e in emojis"
      :key="e"
      class="emoji-item"
      @click="pick(e)"
    >{{ e }}</div>
  </div>
</template>

<script setup>
// 通用 emoji 面板：点选后向父组件 emit('pick', emoji)，父组件负责插入文本/显隐。
const emit = defineEmits(['pick'])

const emojis = [
  '😀', '😃', '😄', '😁', '😆', '😅', '😂', '🤣',
  '😊', '😇', '🙂', '🙃', '😉', '😌', '😍', '🥰',
  '😘', '😋', '😛', '😝', '😜', '🤪', '🤨', '🧐',
  '🤓', '😎', '🥳', '😏', '😒', '😞', '😔', '😟',
  '😕', '🙁', '☹️', '😣', '😖', '😫', '😩', '🥺',
  '😢', '😭', '😤', '😠', '😡', '🤬', '🤯', '😳',
  '🥵', '🥶', '😱', '😨', '😰', '😥', '😓', '🤗',
  '🤔', '🤭', '🤫', '🤥', '😶', '😐', '😑', '😬',
  '🙄', '😯', '😦', '😧', '😮', '😲', '🥱', '😴',
  '🤤', '😪', '😵', '🤐', '🥴', '🤢', '🤮', '🤧',
  '😷', '🤒', '🤕', '🤑', '🤠', '😈', '👿', '🤡',
  '💩', '👻', '💀', '☠️', '👽', '👾', '🤖', '🎃',
  '😺', '😸', '😹', '😻', '😼', '😽', '🙀', '😿',
  '😾', '👋', '🤚', '✋', '🖐', '👌', '🤏', '✌️',
  '🤞', '🤟', '🤘', '🤙', '👈', '👉', '👆', '👇',
  '☝️', '👍', '👎', '✊', '👊', '🤛', '🤜', '👏',
  '🙌', '👐', '🤲', '🤝', '🙏', '✍️', '💪', '🦾',
  '💅', '❤️', '🧡', '💛', '💚', '💙', '💜', '🖤',
  '🤍', '🤎', '💔', '❣️', '💕', '💞', '💓', '💗',
  '💖', '💘', '💝', '💟',
]

function pick(e) {
  emit('pick', e)
}
</script>

<style scoped>
.emoji-picker {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 2px;
  padding: 8px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  width: 296px;
  max-height: 208px;
  overflow-y: auto;
}
.emoji-item {
  text-align: center;
  font-size: 20px;
  line-height: 1.5;
  cursor: pointer;
  border-radius: 4px;
  padding: 2px;
  transition: background 0.15s;
}
.emoji-item:hover {
  background: #f5f7fa;
}
</style>
