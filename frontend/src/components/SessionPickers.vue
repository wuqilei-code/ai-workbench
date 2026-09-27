<script setup lang="ts">
/** 会话级「技能」「工具(MCP)」选择弹窗。
 *  - 技能:内置默认加载,自定义技能按会话勾选(穿梭框 + 分页);
 *  - 工具:未选择时默认加载全部已启用工具,可勾选/恢复默认。
 *  自 ChatView 抽离;父组件通过 ref 调用 openSkills() / openMcp()。 */
import { computed, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import { chatApi } from '@/api/chat'
import { mcpApi } from '@/api/mcp'
import { useChatStore } from '@/stores/chat'

const chat = useChatStore()
const msg = useMessage()
const { t } = useI18n()

// ---------- 会话技能选择 ----------
const customSkills = ref<string[]>([])
const skillsPick = ref<string[]>([])
const skillsOpen = ref(false)
const SKILL_PAGE_SIZE = 8
const skillPage = ref(1)
const skillUnpicked = computed(() => customSkills.value.filter((s) => !skillsPick.value.includes(s)))
const skillPageCount = computed(() => Math.max(1, Math.ceil(skillUnpicked.value.length / SKILL_PAGE_SIZE)))
const skillTransferOptions = computed(() => [
  ...skillsPick.value.map((s) => ({ label: s, value: s })),
  ...skillUnpicked.value.slice((skillPage.value - 1) * SKILL_PAGE_SIZE, skillPage.value * SKILL_PAGE_SIZE).map((s) => ({ label: s, value: s })),
])
watch(() => skillsPick.value.length, () => { if (skillPage.value > skillPageCount.value) skillPage.value = skillPageCount.value })

async function openSkills() {
  try { customSkills.value = await chatApi.customSkills() } catch { customSkills.value = [] }
  skillsPick.value = (chat.currentSession?.skills || []).filter((s) => customSkills.value.includes(s))
  skillPage.value = 1
  skillsOpen.value = true
}
async function applySkills() {
  if (!chat.currentSessionId) return
  try {
    const updated = await chatApi.updateSession(chat.currentSessionId, { skills: skillsPick.value })
    const idx = chat.sessions.findIndex((s) => s.id === chat.currentSessionId)
    if (idx >= 0) chat.sessions[idx] = updated
    skillsOpen.value = false
    msg.success(t('pickers2.skillSaved'))
  } catch (e: any) {
    msg.error(e?.response?.data?.message || e?.message || t('pickers2.saveFailed'))
  }
}

// ---------- 会话工具(MCP)选择 ----------
const mcpOpen = ref(false)
const mcpPick = ref<string[]>([])
const mcpAvail = ref<string[]>([])
/** 名称 → 类型(remote/manual/openapi),用于在勾选列表里标注 */
const mcpTypeMap = ref<Record<string, string>>({})
const mcpSaving = ref(false)
const MCP_PAGE_SIZE = 8
const mcpPage = ref(1)
const mcpUnpicked = computed(() => mcpAvail.value.filter((n) => !mcpPick.value.includes(n)))
const mcpPageCount = computed(() => Math.max(1, Math.ceil(mcpUnpicked.value.length / MCP_PAGE_SIZE)))
const MCP_TYPE_LABEL: Record<string, string> = { remote: 'MCP', manual: t('pickers2.mcpManual'), openapi: 'OpenAPI' }
function mcpLabel(n: string): string {
  const t = mcpTypeMap.value[n]
  return t && MCP_TYPE_LABEL[t] ? `${n} · ${MCP_TYPE_LABEL[t]}` : n
}
const mcpTransferOptions = computed(() => [
  ...mcpPick.value.map((n) => ({ label: mcpLabel(n), value: n })),
  ...mcpUnpicked.value.slice((mcpPage.value - 1) * MCP_PAGE_SIZE, mcpPage.value * MCP_PAGE_SIZE).map((n) => ({ label: mcpLabel(n), value: n })),
])
watch(() => mcpPick.value.length, () => { if (mcpPage.value > mcpPageCount.value) mcpPage.value = mcpPageCount.value })

async function openMcp() {
  try {
    const items = await mcpApi.selectable()
    mcpAvail.value = items.map((i) => i.name)
    mcpTypeMap.value = Object.fromEntries(items.map((i) => [i.name, i.type]))
  } catch {
    mcpAvail.value = []
    mcpTypeMap.value = {}
    msg.error(t('pickers2.mcpLoadFailed'))
    return
  }
  const cur = chat.currentSession?.mcpServers
  // 未设置(null)=默认全部已启用(全选展示);显式选择则沿用;空数组=本会话不加载
  mcpPick.value = cur == null ? [...mcpAvail.value] : [...cur]
  mcpPage.value = 1
  mcpOpen.value = true
}
async function applyMcp() {
  if (!chat.currentSessionId) return
  if (mcpSaving.value) return
  mcpSaving.value = true
  try {
    const updated = await chatApi.updateSession(chat.currentSessionId, { mcpServers: mcpPick.value })
    const idx = chat.sessions.findIndex((s) => s.id === chat.currentSessionId)
    if (idx >= 0) chat.sessions[idx] = updated
    mcpOpen.value = false
    msg.success(t('pickers2.mcpSaved'))
  } catch (e: any) {
    msg.error(e?.response?.data?.message || e?.message || t('pickers2.saveFailed'))
  } finally {
    mcpSaving.value = false
  }
}
async function resetMcp() {
  if (!chat.currentSessionId) return
  if (mcpSaving.value) return
  mcpSaving.value = true
  try {
    const updated = await chatApi.updateSession(chat.currentSessionId, { resetMcpServers: true })
    const idx = chat.sessions.findIndex((s) => s.id === chat.currentSessionId)
    if (idx >= 0) chat.sessions[idx] = updated
    mcpOpen.value = false
    msg.success(t('pickers2.mcpRestored'))
  } catch (e: any) {
    msg.error(e?.response?.data?.message || e?.message || t('pickers2.saveFailed'))
  } finally {
    mcpSaving.value = false
  }
}

defineExpose({ openSkills, openMcp })
</script>

<template>
  <n-modal v-model:show="skillsOpen" preset="card" style="width: 780px; max-width: 96vw" :bordered="false">
    <template #header>
      <div class="picker-head">
        <span class="picker-ico picker-ico-skill">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>
        </span>
        <div class="picker-head-text">
          <div class="picker-title">{{ t('pickers.skillsTitle') }}</div>
          <div class="picker-sub">{{ t('pickers.skillsSub') }}</div>
        </div>
        <n-tag v-if="skillsPick.length" size="small" round type="primary" :bordered="false" class="picker-count">{{ skillsPick.length }}{{ t('pickers.skillsCount') }}</n-tag>
        <n-tag v-else size="small" round :bordered="false" class="picker-count">{{ t('pickers.skillsNone') }}</n-tag>
      </div>
    </template>
    <div class="picker-body">
      <div class="picker-desc">{{ t('pickers.skillsDesc') }}</div>
      <n-transfer v-model:value="skillsPick" :options="skillTransferOptions" source-filterable :source-title="t('pickers2.skillSourceTitle')" :target-title="t('pickers2.skillTargetTitle')" class="picker-transfer" />
      <div v-if="skillUnpicked.length > SKILL_PAGE_SIZE" class="picker-pager">
        <n-pagination :page="skillPage" :page-count="skillPageCount" size="small" @update:page="skillPage = $event" />
      </div>
    </div>
    <div class="sp-actions">
      <span class="sp-tip">{{ t('pickers.tip') }}</span>
      <div class="sp-action-btns">
        <n-button size="medium" quaternary @click="skillsOpen = false">{{ t('common.cancel') }}</n-button>
        <n-button size="medium" type="primary" @click="applySkills">{{ t('common.apply') }}</n-button>
      </div>
    </div>
  </n-modal>

  <n-modal v-model:show="mcpOpen" preset="card" style="width: 780px; max-width: 96vw" :bordered="false">
    <template #header>
      <div class="picker-head">
        <span class="picker-ico picker-ico-mcp">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>
        </span>
        <div class="picker-head-text">
          <div class="picker-title">{{ t('pickers.mcpTitle') }}</div>
          <div class="picker-sub">{{ t('pickers.mcpSub') }}</div>
        </div>
        <n-tag v-if="mcpPick.length" size="small" round type="primary" :bordered="false" class="picker-count">{{ mcpPick.length }}{{ t('pickers.mcpCount') }}</n-tag>
        <n-tag v-else size="small" round :bordered="false" class="picker-count">{{ t('pickers.mcpNone') }}</n-tag>
      </div>
    </template>
    <div class="picker-body">
      <div class="picker-desc">{{ t('pickers.mcpDesc') }}</div>
      <n-transfer v-model:value="mcpPick" :options="mcpTransferOptions" source-filterable :source-title="t('pickers2.mcpSourceTitle')" :target-title="t('pickers2.mcpTargetTitle')" class="picker-transfer" />
      <div v-if="mcpUnpicked.length > MCP_PAGE_SIZE" class="picker-pager">
        <n-pagination :page="mcpPage" :page-count="mcpPageCount" size="small" @update:page="mcpPage = $event" />
      </div>
    </div>
    <div class="sp-actions">
      <span class="sp-tip">{{ t('pickers.tip') }}</span>
      <div class="sp-action-btns">
        <n-button size="medium" quaternary :disabled="mcpSaving" class="reset-btn" @click="resetMcp">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right:4px"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/></svg>
          {{ t('pickers2.restoreDefault') }}</n-button>
        <n-button size="medium" quaternary @click="mcpOpen = false">{{ t('common.cancel') }}</n-button>
        <n-button size="medium" type="primary" :loading="mcpSaving" :disabled="mcpSaving" @click="applyMcp">{{ t('common.apply') }}</n-button>
      </div>
    </div>
  </n-modal>
</template>

<style scoped>
/* ---- 会话技能 / 会话 MCP 对话框(大气版) ---- */
.picker-head { display: flex; align-items: center; gap: 14px; width: 100%; padding-right: 8px; }
.picker-ico {
  flex-shrink: 0; width: 44px; height: 44px; border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 6px 18px rgba(0,0,0,.10);
}
.picker-ico-skill { background: linear-gradient(135deg,#4a4d55,#2a2c31); color: #fff; }
.picker-ico-mcp { background: linear-gradient(135deg,#0ea5e9,#38bdf8); color: #fff; }
.picker-head-text { flex: 1; min-width: 0; }
.picker-title { font-size: 17px; font-weight: 700; color: var(--text); line-height: 1.3; }
.picker-sub { font-size: 12px; color: var(--text-3); margin-top: 2px; }
.picker-count { font-size: 12px; }

.picker-body { padding: 4px 2px 2px; }
.picker-body :deep(.n-transfer-list) { height: 300px; }
.picker-body :deep(.n-transfer-list-header__title) { font-size: 13px; font-weight: 600; }
.picker-pager { display: flex; justify-content: flex-end; padding: 10px 2px 0; }
.picker-desc {
  font-size: 13px; color: var(--text-2); line-height: 1.7;
  background: var(--surface-hover); border: 1px solid var(--border-soft);
  border-radius: 10px; padding: 10px 14px; margin-bottom: 14px;
}
.picker-desc em { font-style: normal; font-weight: 600; color: var(--accent); }

.sp-actions {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; margin-top: 16px; padding-top: 14px;
  border-top: 1px solid var(--border-soft);
}
.sp-tip { font-size: 12px; color: var(--text-3); }
.sp-action-btns { display: flex; align-items: center; gap: 8px; }
.sp-action-btns .reset-btn { margin-right: auto; }
</style>
