<script setup lang="ts">
defineProps<{ src: string; path?: string }>()
</script>

<template>
  <div class="image-wrap">
    <img :src="src" :alt="path || ''" class="preview-image" />
  </div>
</template>

<style scoped>
.image-wrap {
  display: flex; align-items: center; justify-content: center;
  overflow: auto; height: 100%; padding: 16px;
  background: #18181c;
}
.preview-image {
  max-width: 100%; max-height: 100%;
  object-fit: contain; border-radius: 4px;
}
</style>
