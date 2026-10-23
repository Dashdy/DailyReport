<template>
  <!-- 单张图片：有图显示图片（可点开大图），可编辑时可以上传 / 更换 / 删除 -->
  <div class="image-upload" :class="{ empty: !modelValue }">
    <el-image v-if="modelValue" :src="fileUrl(modelValue)" :preview-src-list="[fileUrl(modelValue)]"
              fit="cover" class="img" preview-teleported />
    <div v-else class="placeholder">
      <el-icon :size="28"><Picture /></el-icon>
      <span>{{ placeholder }}</span>
    </div>

    <div v-if="editable" class="actions">
      <el-upload :show-file-list="false" accept="image/*" :http-request="doUpload">
        <el-button size="small" type="primary" :loading="uploading">{{ modelValue ? '更换' : '上传' }}</el-button>
      </el-upload>
      <el-button v-if="modelValue" size="small" @click="emit('update:modelValue', '')">移除</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '@/api'
import { fileUrl } from '@/utils/format'

defineProps({
  modelValue: { type: String, default: '' },
  editable: { type: Boolean, default: false },
  placeholder: { type: String, default: '暂无照片' }
})
const emit = defineEmits(['update:modelValue'])

const uploading = ref(false)

async function doUpload({ file }) {
  uploading.value = true
  try {
    const res = await api.upload(file)
    if (!res.success) {
      ElMessage.error(res.message)
      return
    }
    emit('update:modelValue', res.data.url)
  } finally {
    uploading.value = false
  }
}
</script>

<style scoped>
.image-upload {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.img,
.placeholder {
  width: 100%;
  aspect-ratio: 4 / 3;
  max-width: 100%;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.12);
}

.placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px dashed rgba(255, 255, 255, 0.5);
  font-size: 13px;
  color: rgba(255, 255, 255, 0.85);
}

.actions {
  display: flex;
  gap: 8px;
}
</style>
