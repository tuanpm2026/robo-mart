<script setup lang="ts">
import { ref, watch, computed, onUnmounted } from 'vue'
import { useToast } from 'primevue/usetoast'
import FileUpload from 'primevue/fileupload'
import Button from 'primevue/button'
import { uploadImages, deleteImage, reorderImages, type ProductImage } from '@/api/productAdminApi'

const props = defineProps<{
  productId: number | null
  existingImages: ProductImage[]
}>()

const emit = defineEmits<{
  'update:existingImages': [images: ProductImage[]]
  pendingFiles: [files: File[]]
  imagesChanged: []
}>()

const toast = useToast()

const localImages = ref<ProductImage[]>([...props.existingImages])
const localPreviews = ref<string[]>([])
const pendingFiles = ref<File[]>([])
const isUploading = ref(false)
const isReordering = ref(false)
const dragIndex = ref<number | null>(null)

// Sync with parent prop immediately on mount and on updates
watch(
  () => props.existingImages,
  (images) => {
    localImages.value = [...images]
  },
  { immediate: true },
)

const remainingSlots = computed(() => {
  const used =
    localImages.value.length + (props.productId === null ? localPreviews.value.length : 0)
  return Math.max(0, 10 - used)
})

const atImageLimit = computed(() => remainingSlots.value === 0)

// Drag-and-drop reorder
function onDragStart(index: number) {
  dragIndex.value = index
}

function onDragOver(e: DragEvent) {
  e.preventDefault()
}

async function onDrop(index: number) {
  if (dragIndex.value === null || dragIndex.value === index || isReordering.value) return
  const fromIndex = dragIndex.value
  dragIndex.value = null

  const items = [...localImages.value]
  const [moved] = items.splice(fromIndex, 1)
  if (!moved) return
  items.splice(index, 0, moved)
  // Update localImages immediately to avoid ghost visual state
  localImages.value = items.map((img, i) => ({ ...img, displayOrder: i }))

  if (props.productId !== null) {
    isReordering.value = true
    try {
      const reorderItems = localImages.value.map((img, i) => ({ imageId: img.id, displayOrder: i }))
      const updated = await reorderImages(props.productId, reorderItems)
      emit('update:existingImages', updated)
      emit('imagesChanged')
    } catch {
      toast.add({ severity: 'error', summary: 'Reorder failed', life: 3000 })
    } finally {
      isReordering.value = false
    }
  } else {
    emit('update:existingImages', localImages.value)
  }
}

// File selection handler
async function onFilesSelected(event: { files: File[] }) {
  const files = event.files
  if (!files || files.length === 0) return

  if (props.productId !== null) {
    // Edit mode — upload immediately; guard against concurrent selections
    if (isUploading.value) return
    isUploading.value = true
    try {
      const newImages = await uploadImages(props.productId, files)
      const updated = [...localImages.value, ...newImages]
      localImages.value = updated
      emit('update:existingImages', updated)
      emit('imagesChanged')
      toast.add({ severity: 'success', summary: `${files.length} image(s) uploaded`, life: 3000 })
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Upload failed'
      toast.add({ severity: 'error', summary: 'Upload failed', detail: message, life: 5000 })
    } finally {
      isUploading.value = false
    }
  } else {
    // Create mode — accumulate pending files, show local previews
    localPreviews.value.forEach((url) => URL.revokeObjectURL(url))
    const accumulated = [...pendingFiles.value, ...files]
    pendingFiles.value = accumulated
    emit('pendingFiles', accumulated)
    localPreviews.value = accumulated.map((f) => URL.createObjectURL(f))
  }
}

// Delete image
async function removeImage(image: ProductImage) {
  if (props.productId !== null) {
    try {
      await deleteImage(props.productId, image.id)
    } catch {
      toast.add({ severity: 'error', summary: 'Delete failed', life: 3000 })
      return
    }
  }
  // Update localImages immediately before any subsequent drag to prevent ghost state
  localImages.value = localImages.value.filter((i) => i.id !== image.id)
  emit('update:existingImages', localImages.value)
  if (props.productId !== null) {
    emit('imagesChanged')
  }
}

onUnmounted(() => {
  localPreviews.value.forEach((url) => URL.revokeObjectURL(url))
})
</script>

<template>
  <div class="image-upload-section">
    <label class="field-label"
      >Product Images <span class="text-gray-400 text-xs">(optional)</span></label
    >

    <!-- Existing images grid -->
    <div v-if="localImages.length > 0" class="images-grid">
      <div
        v-for="(image, index) in localImages"
        :key="image.id"
        class="image-card"
        :class="{ 'is-reordering': isReordering }"
        draggable="true"
        @dragstart="onDragStart(index)"
        @dragover="onDragOver"
        @drop="onDrop(index)"
      >
        <div class="image-drag-handle">
          <i class="pi pi-bars" />
        </div>
        <img :src="image.imageUrl" :alt="image.altText ?? 'Product image'" class="image-thumb" />
        <div v-if="index === 0" class="primary-badge">Primary</div>
        <Button
          icon="pi pi-times"
          severity="danger"
          size="small"
          text
          class="image-delete-btn"
          :disabled="isUploading || isReordering"
          @click="removeImage(image)"
        />
      </div>
    </div>

    <!-- Create mode previews -->
    <div v-if="localPreviews.length > 0 && productId === null" class="images-grid">
      <div v-for="(previewUrl, index) in localPreviews" :key="index" class="image-card">
        <img :src="previewUrl" alt="Preview" class="image-thumb" />
        <div v-if="index === 0" class="primary-badge">Primary</div>
      </div>
    </div>

    <!-- FileUpload component -->
    <div class="upload-wrapper" :class="{ 'is-uploading': isUploading }">
      <FileUpload
        name="files"
        :multiple="true"
        accept="image/jpeg,image/png,image/webp"
        :max-file-size="5242880"
        custom-upload
        mode="advanced"
        :show-upload-button="false"
        choose-label="Select Images"
        :disabled="atImageLimit || isUploading"
        @select="onFilesSelected"
      >
        <template #empty>
          <div class="upload-empty">
            <i
              v-if="isUploading"
              class="pi pi-spinner pi-spin"
              style="font-size: 2rem; color: var(--color-primary-500)"
            />
            <i v-else class="pi pi-image" style="font-size: 2rem; color: var(--color-gray-400)" />
            <p v-if="isUploading">Uploading…</p>
            <p v-else-if="atImageLimit" class="text-warning">10 image limit reached</p>
            <p v-else>Drag and drop images here, or click Select Images</p>
            <p v-if="!atImageLimit && !isUploading" class="upload-hint">
              JPEG, PNG, WebP · max 5MB per file · {{ remainingSlots }} slot{{
                remainingSlots === 1 ? '' : 's'
              }}
              remaining
            </p>
          </div>
        </template>
      </FileUpload>
    </div>
  </div>
</template>

<style scoped>
.image-upload-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-gray-700);
}

.images-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.image-card {
  position: relative;
  width: 80px;
  height: 80px;
  border: 1px solid var(--color-gray-200);
  border-radius: 6px;
  overflow: hidden;
  cursor: grab;
}

.image-card:active {
  cursor: grabbing;
}

.image-card.is-reordering {
  opacity: 0.7;
  cursor: not-allowed;
}

.image-drag-handle {
  position: absolute;
  top: 2px;
  left: 2px;
  color: white;
  background: rgba(0, 0, 0, 0.4);
  border-radius: 3px;
  padding: 2px 3px;
  font-size: 10px;
  z-index: 1;
}

.image-thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.primary-badge {
  position: absolute;
  bottom: 2px;
  left: 2px;
  background: var(--color-primary-600, #2563eb);
  color: white;
  font-size: 9px;
  font-weight: 600;
  padding: 1px 4px;
  border-radius: 3px;
  text-transform: uppercase;
}

.image-delete-btn {
  position: absolute;
  top: 0;
  right: 0;
  padding: 2px !important;
  width: 20px !important;
  height: 20px !important;
}

.upload-wrapper {
  position: relative;
}

.upload-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 16px;
  color: var(--color-gray-500);
  font-size: 13px;
}

.upload-hint {
  font-size: 11px;
  color: var(--color-gray-400);
}

.text-warning {
  color: var(--color-warning-600, #d97706);
  font-size: 12px;
}
</style>
