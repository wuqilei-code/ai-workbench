<script setup lang="ts">
defineProps<{ src: string }>()
</script>

<template>
  <iframe :src="src" class="preview-pdf" title="PDF preview" />
</template>

<style scoped>
.preview-pdf {
  width: 100%; height: 100%; border: 0;
  background: #525659;
}
</style>
