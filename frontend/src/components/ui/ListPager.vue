<script setup lang="ts">
import { computed } from 'vue'

/**
 * 分页条(替代 11 处重复的 `<div class="*-pager"><n-pagination/></div>` + 对齐样式)。
 * 仅当总页数 > 1 时渲染;align 控制居中/靠右。
 */
const props = withDefaults(
  defineProps<{
    page: number
    /** 总页数模式:传总页数;与 itemCount 二选一 */
    pageCount?: number
    /** item-count 模式:传总数,内部按 pageSize 算页数 */
    itemCount?: number
    pageSize?: number
    align?: 'center' | 'right'
  }>(),
  { pageCount: 0, itemCount: 0, pageSize: 0, align: 'center' },
)
const emit = defineEmits<{ (e: 'update:page', v: number): void }>()

const total = computed(() =>
  props.itemCount > 0 && props.pageSize > 0
    ? Math.max(1, Math.ceil(props.itemCount / props.pageSize))
    : props.pageCount,
)
</script>

<template>
  <div v-if="total > 1" class="list-pager" :class="align">
    <n-pagination
      :page="page"
      :page-count="total"
      :item-count="itemCount || undefined"
      :page-size="itemCount ? pageSize : undefined"
      size="small"
      @update:page="(v: number) => emit('update:page', v)"
    />
  </div>
</template>

<style scoped>
.list-pager { display: flex; padding: 14px 0 4px; flex-shrink: 0; }
.list-pager.center { justify-content: center; }
.list-pager.right { justify-content: flex-end; }
</style>
