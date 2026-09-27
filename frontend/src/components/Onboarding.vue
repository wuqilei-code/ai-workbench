<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const LS_KEY = 'aiw.onboarded.v1'

/** 首次登录(未看过引导)时展示 3 步引导 */
const steps = [
  { title: t('onboarding.f1'), desc: t('onboarding.f1Desc'), icon: '💬' },
  { title: t('onboarding.f2'), desc: t('onboarding.f2Desc'), icon: '📎' },
  { title: t('onboarding.f3'), desc: t('onboarding.f3Desc'), icon: '⭐' },
]

const show = ref(false)
const current = ref(0)

defineExpose({ tryShowOnboarding })

const total = computed(() => steps.length)
const isLast = computed(() => current.value >= total.value - 1)
const progress = computed(() => Math.round(((current.value + 1) / total.value) * 100))

/** 挂载后检查:未引导过则弹出 */
function tryShowOnboarding() {
  try {
    if (localStorage.getItem(LS_KEY)) return
    show.value = true
  } catch {
    /* ignore */
  }
}

function next() {
  if (isLast.value) {
    close()
  } else {
    current.value++
  }
}
function prev() {
  if (current.value > 0) current.value--
}
function close() {
  show.value = false
  try {
    localStorage.setItem(LS_KEY, '1')
  } catch {
    /* ignore */
  }
}
</script>

<template>
  <n-modal
    v-model:show="show"
    preset="card"
    :closable="false"
    :mask-closable="false"
    :show-close="false"
    style="width: 460px; max-width: 92vw"
    :bordered="false"
    :title="t('onboarding.title')"
  >
    <div class="ob-body">
      <n-progress
        type="line"
        :percentage="progress"
        :height="6"
        :show-indicator="false"
        color="#6b8afd"
        rail-color="var(--border-soft)"
      />
      <div class="ob-step">
        <div class="ob-icon">{{ steps[current].icon }}</div>
        <div class="ob-title">{{ steps[current].title }}</div>
        <div class="ob-desc">{{ steps[current].desc }}</div>
      </div>
      <div class="ob-dots">
        <span
          v-for="(s, i) in steps"
          :key="i"
          class="ob-dot"
          :class="{ active: i === current }"
          @click="current = i"
        />
      </div>
    </div>
    <template #footer>
      <div class="ob-footer">
        <span v-if="current > 0" class="ob-skip" @click="close">{{ t('onboarding.skip') }}</span>
        <span v-else class="ob-skip" />
        <div style="display: flex; gap: 8px">
          <n-button v-if="current > 0" quaternary @click="prev">{{ t('onboarding.prev') }}</n-button>
          <n-button type="primary" @click="next">{{ isLast ? t('onboarding.start') : t('onboarding.next') }}</n-button>
        </div>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.ob-body { padding: 4px 0 8px; }
.ob-step { text-align: center; padding: 20px 8px 8px; }
.ob-icon { font-size: 40px; margin-bottom: 12px; }
.ob-title { font-size: 17px; font-weight: 700; color: var(--text); margin-bottom: 10px; }
.ob-desc { font-size: 13px; color: var(--text-2); line-height: 1.8; }
.ob-dots { display: flex; justify-content: center; gap: 6px; margin-top: 18px; }
.ob-dot {
  width: 8px; height: 8px; border-radius: 50%; cursor: pointer;
  background: var(--border); transition: background .2s, width .2s;
}
.ob-dot.active { width: 20px; border-radius: 4px; background: var(--accent); }
.ob-footer { display: flex; align-items: center; justify-content: space-between; }
.ob-skip { font-size: 12px; color: var(--text-3); cursor: pointer; padding: 4px; }
.ob-skip:hover { color: var(--text-2); }
</style>
