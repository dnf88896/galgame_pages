<template>
  <div v-if="attachments && attachments.length" class="attachments">
    <div class="attachments-head">
      <strong>附件 {{ attachments.length }}</strong>
    </div>
    <div class="attachments-grid">
      <div v-for="att in attachments" :key="att.id" class="attachment">
        <div class="attachment-media">
          <el-image
            v-if="isImage(att.mime_type)"
            :src="urlOf(att)"
            :preview-src-list="imagePreviewList"
            :initial-index="imageIndex(att)"
            fit="contain"
            class="attachment-image"
          />
          <audio
            v-else-if="isAudio(att.mime_type)"
            controls
            preload="metadata"
            :src="urlOf(att)"
          />
          <video
            v-else-if="isVideo(att.mime_type)"
            controls
            preload="metadata"
            :src="urlOf(att)"
          />
          <div v-else class="attachment-file">
            <span>{{ displayType(att.mime_type) }}</span>
          </div>
        </div>
        <div class="attachment-name" :title="att.original_name">{{ att.original_name }}</div>
        <div class="attachment-meta">{{ formatSize(att.size) }}</div>
        <a class="attachment-link" :href="urlOf(att)" :download="att.original_name">下载文件</a>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { formatSize, resolveAssetUrl } from '../utils/format'

const props = defineProps({
  attachments: { type: Array, default: () => [] },
})

const isImage = (type) => (type || '').startsWith('image/')
const isAudio = (type) => (type || '').startsWith('audio/')
const isVideo = (type) => (type || '').startsWith('video/')

function urlOf(att) {
  return resolveAssetUrl(att && att.url)
}

const imagePreviewList = computed(() =>
  (props.attachments || []).filter((a) => isImage(a.mime_type)).map((a) => urlOf(a)),
)

function imageIndex(att) {
  const images = (props.attachments || []).filter((a) => isImage(a.mime_type))
  return Math.max(0, images.indexOf(att))
}

function displayType(mime) {
  if (!mime) return '文件'
  const ext = mime.split('/').pop()
  return (ext || '文件').toUpperCase()
}
</script>

<style scoped>
.attachments {
  margin-top: 12px;
}
.attachments-head {
  font-size: 13px;
  color: #666;
  margin-bottom: 8px;
}
.attachments-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}
.attachment {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 8px;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.attachment-media {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 40px;
  background: #f5f7fa;
  border-radius: 6px;
  overflow: hidden;
}
.attachment-image {
  width: 100%;
  height: 160px;
}
.attachment-media audio,
.attachment-media video {
  width: 100%;
  max-height: 200px;
}
.attachment-file {
  padding: 24px 8px;
  color: #909399;
  font-size: 13px;
  text-align: center;
}
.attachment-name {
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.attachment-meta {
  font-size: 12px;
  color: #909399;
}
.attachment-link {
  font-size: 12px;
  color: #409eff;
  text-decoration: none;
}
.attachment-link:hover {
  text-decoration: underline;
}
</style>
