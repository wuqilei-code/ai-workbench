<script setup lang="ts">
/** 工作区「技能」面板:只展示**我的技能**(我安装的 + 我创建的)+ 内置技能只读折叠区。
 *  技能市场(SkillMarketplace)是独立页面(侧边栏/管理页入口),此处不再内嵌——
 *  两处并存会让「市场-我的」与「工作区-我的」计数看起来不一致(发布路径历史缺陷导致)。
 *  自 ChatView 抽离,降低巨型组件体积。 */
import { computed, onMounted, ref, watch } from 'vue'
import { useDialog, useMessage } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import { chatApi } from '@/api/chat'
import type { SkillInfo, SkillFileVo, UserSkill } from '@/types'
import SkillEditor from '@/components/SkillEditor.vue'
import IconPicker from '@/components/ui/IconPicker.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ListPager from '@/components/ui/ListPager.vue'

const msg = useMessage()
const dialog = useDialog()
const { t } = useI18n()

/** 我的技能分页:工作区面板宽度有限,不分页时卡片会拉得很长 */
const userPageSize = 8
const userPage = ref(1)
const pagedUserSkills = computed(() => {
  const start = (userPage.value - 1) * userPageSize
  return userSkills.value.slice(start, start + userPageSize)
})

// ---------- 内置技能(只读) ----------
const builtinSkills = ref<SkillInfo[]>([])
const builtinLoading = ref(false)
async function loadBuiltinSkills() {
  builtinLoading.value = true
  try {
    builtinSkills.value = await chatApi.builtinSkills()
  } catch {
    builtinSkills.value = []
  } finally {
    builtinLoading.value = false
  }
}

// ---------- 我的技能(user_skill:自建/安装) ----------
const userSkills = ref<UserSkill[]>([])
// 删除后当前页可能空掉,收敛回最后一页
watch(userSkills, (list) => {
  const max = Math.max(1, Math.ceil(list.length / userPageSize))
  if (userPage.value > max) userPage.value = max
})
async function loadUserSkills() {
  try {
    userSkills.value = await chatApi.listMineSkills()
  } catch {
    userSkills.value = []
  }
}

/** 从 SKILL.md frontmatter 提取 icon;无则按名字哈希取兜底 emoji */
function parseSkillIcon(content: string | undefined): string {
  if (!content) return ''
  const m = content.match(/^---\s*\n([\s\S]*?)\n---/)
  if (m) {
    const icon = m[1].match(/^icon\s*:\s*(.+)$/m)?.[1]?.trim()
    if (icon) return icon
  }
  return ''
}
function fallbackIcon(name: string): string {
  const pool = ['🤖', '🧩', '📊', '📄', '✍️', '🗂️', '💻', '🔬', '🎨', '🚀', '🛠️', '📝']
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  return pool[h % pool.length]
}

/** 把 icon 注入 SKILL.md frontmatter(已有 icon 则替换;无 frontmatter 则创建) */
function injectIconIntoFrontmatter(content: string, icon: string, name: string, desc: string): string {
  if (!icon) return content
  const c = content || ''
  const m = c.match(/^---\s*\n([\s\S]*?)\n---/)
  if (m) {
    if (m[1].includes('icon:')) return c.replace(/^(icon\s*:\s*).+$/m, `$1${icon}`)
    return c.replace(/^(---\s*\n(?:[\s\S]*?\n))/, `$1icon: ${icon}\n`)
  }
  const d = (desc || '').replace(/\n/g, ' ').trim() || t('skillsPanel2.noDescFallback')
  return `---\nname: ${name}\ndescription: ${d}\nicon: ${icon}\n---\n\n# ${name}\n\n(在此描述技能功能)。\n`
}

const skillModalShow = ref(false)
const skillEditingId = ref<number | null>(null)
const skillReadonly = ref(false)
const skillForm = ref<{ name: string; description: string; content: string; visibility: number; icon: string; files: SkillFileVo[] }>({
  name: '', description: '', content: '', visibility: 0, icon: '', files: [],
})
function openCreateSkill() {
  skillEditingId.value = null
  skillReadonly.value = false
  skillForm.value = { name: '', description: '', content: '', visibility: 0, icon: '🤖', files: [] }
  skillModalShow.value = true
}
function openEditSkill(s: UserSkill) {
  skillEditingId.value = s.id
  // 市场安装的技能只读,点击打开查看详情
  skillReadonly.value = s.source === 'installed'
  skillForm.value = {
    name: s.name, description: s.description || '',
    content: s.content || '', visibility: s.visibility || 0,
    icon: parseSkillIcon(s.content) || fallbackIcon(s.name),
    files: (s.files || []).map((f) => ({ path: f.path, content: f.content || '', encoding: f.encoding })),
  }
  skillModalShow.value = true
}
async function saveSkill() {
  try {
    const contentWithIcon = injectIconIntoFrontmatter(
      skillForm.value.content, skillForm.value.icon, skillForm.value.name.trim() || 'skill', skillForm.value.description,
    )
    if (skillEditingId.value) {
      await chatApi.updateSkill(skillEditingId.value, {
        description: skillForm.value.description,
        content: contentWithIcon,
        visibility: skillForm.value.visibility,
        files: skillForm.value.files.filter((f) => f.path && !f.path.startsWith('SKILL.md')),
      })
    } else {
      await chatApi.createSkill({
        name: skillForm.value.name.trim(),
        description: skillForm.value.description,
        content: contentWithIcon,
        visibility: skillForm.value.visibility,
        files: skillForm.value.files.filter((f) => f.path),
      })
    }
    skillModalShow.value = false
    await loadUserSkills()
    msg.success(t('skillsPanel2.saved'))
  } catch (e: any) {
    msg.error(e?.response?.data?.message || e?.message || t('skillsPanel2.saveFailed'))
  }
}
async function removeUserSkill(s: UserSkill) {
  const ok = await new Promise<boolean>((resolve) => {
    dialog.warning({
    title: t('skillsPanel2.delTitle'),
    content: s.source === 'installed' ? t('skillsPanel2.delInstalled', { name: s.name }) : t('skillsPanel2.delOwn', { name: s.name }),
    positiveText: t('skillsPanel2.delBtn'), negativeText: t('skillsPanel2.cancel'),
      onPositiveClick: () => resolve(true), onNegativeClick: () => resolve(false), onClose: () => resolve(false),
    })
  })
  if (!ok) return
  try {
    await chatApi.deleteSkill(s.id)
    await loadUserSkills()
    msg.success(t('skillsPanel2.deleted'))
  } catch (e: any) {
    msg.error(e?.response?.data?.message || e?.message || t('skillsPanel2.delFailed'))
  }
}

/** 市场安装后刷新「我的」列表 */
function onInstalled() {
  loadUserSkills()
}

onMounted(() => {
  loadBuiltinSkills()
  loadUserSkills()
})
</script>

<template>
  <div class="skills-panel">
    <!-- 我的技能:仅「我安装的 + 我创建的」;技能市场是独立页面(见侧边栏/管理页),不在此内嵌,
         避免「市场-我的」与「工作区-我的」两处计数看起来不一致。 -->
    <div class="skill-pane">
      <div class="user-skills">
        <div class="us-head">
          <span class="us-title">{{ t('skillsPanel.mySkills') }}</span>
          <n-button size="tiny" type="primary" secondary @click="openCreateSkill">{{ t('skillsPanel.newSkill') }}</n-button>
        </div>
        <EmptyState v-if="!userSkills.length" :text="t('skillsPanel.empty')" :padding="32">
          <n-button size="small" type="primary" secondary @click="openCreateSkill">{{ t('skillsPanel.newSkill') }}</n-button>
        </EmptyState>
        <div v-for="s in pagedUserSkills" :key="s.id" class="us-card">
          <div class="us-card-icon">{{ parseSkillIcon(s.content) || fallbackIcon(s.name) }}</div>
          <div class="us-card-body">
            <div class="us-card-name">{{ s.name }}</div>
            <div class="us-card-tags">
        <span class="us-tag" :class="s.source === 'installed' ? 'inst' : 'own'">{{ s.source === 'installed' ? t('skillsPanel2.tagInstalled') : t('skillsPanel2.tagOwn') }}</span>
        <span class="us-tag" :class="s.visibility === 1 ? 'pub' : 'pri'">{{ s.visibility === 1 ? t('skillsPanel2.visPublic') : t('skillsPanel2.visPrivate') }}</span>
            </div>
            <div class="us-desc">{{ s.description }}</div>
          </div>
          <div class="us-actions">
        <button class="us-act" :title="s.source === 'installed' ? t('skillsPanel2.viewInstalled') : t('skillsPanel2.editBtn')" @click="openEditSkill(s)">{{ s.source === 'installed' ? t('skillsPanel2.viewBtn') : t('skillsPanel2.editBtn') }}</button>
        <button class="us-act danger" @click="removeUserSkill(s)">{{ t('skillsPanel2.delBtn') }}</button>
          </div>
        </div>
        <ListPager v-if="userSkills.length > userPageSize" class="us-pager"
          :page="userPage" :item-count="userSkills.length" :page-size="userPageSize"
          @update:page="userPage = $event" />
      </div>

      <n-collapse :default-expanded-names="[]" :bordered="false" class="builtin-collapse">
        <n-collapse-item name="builtin" :title="t('skillsPanel.builtin')">
          <template #header-extra>
            <span class="builtin-note">{{ builtinLoading ? t('skillsPanel.loading') : builtinSkills.length + t('skillsPanel.builtinCount') }}</span>
          </template>
          <div v-if="builtinLoading" class="builtin-empty">{{ t('skillsPanel.loading') }}</div>
          <div v-else-if="!builtinSkills.length" class="builtin-empty">{{ t('skillsPanel.builtinEmpty') }}</div>
          <div v-for="s in builtinSkills" :key="s.name" class="builtin-item">
            <span class="builtin-name">{{ s.name }}</span>
            <span class="builtin-desc">{{ s.description }}</span>
          </div>
        </n-collapse-item>
      </n-collapse>
    </div>

    <!-- 新建/编辑/查看 技能弹窗 -->
    <n-modal v-model:show="skillModalShow" preset="card" :title="skillReadonly ? t('skillsPanel2.viewTitle') : skillEditingId ? t('skillsPanel2.editTitle') : t('skillsPanel2.newTitle')" style="width: 880px; max-width: 96vw" :bordered="false">
      <div class="sk-form">
        <div class="sk-row">
          <span class="sk-label">{{ t('skillsPanel2.labelName') }}</span>
          <n-input v-model:value="skillForm.name" :disabled="skillReadonly || !!skillEditingId" placeholder="note-summarizer" />
        </div>
        <div class="sk-row">
          <span class="sk-label">{{ t('skillsPanel2.labelDesc') }}</span>
          <n-input v-model:value="skillForm.description" :disabled="skillReadonly" :placeholder="t('skillsPanel2.phDesc')" />
        </div>
        <div class="sk-row">
          <span class="sk-label">{{ t('skillsPanel2.labelVisibility') }}</span>
          <n-select
            v-model:value="skillForm.visibility"
            :disabled="skillReadonly"
            :options="[{ label: t('skillsPanel2.visPrivateOpt'), value: 0 }, { label: t('skillsPanel2.visPublicOpt'), value: 1 }]"
            :placeholder="t('skillsPanel2.phVisibility')"
          />
        </div>
        <div class="sk-row" v-if="!skillReadonly">
          <span class="sk-label">{{ t('skillsPanel2.labelIcon') }}</span>
          <IconPicker v-model="skillForm.icon" :size="30" :title="t('skillsPanel2.labelIcon')" />
        </div>
        <div class="sk-row">
          <span class="sk-label">{{ t('skillsPanel2.labelContent') }}</span>
        </div>
        <SkillEditor
          :content="skillForm.content"
          :files="skillForm.files"
          :name="skillForm.name.trim() || 'unnamed-skill'"
          :description="skillForm.description"
          :readonly="skillReadonly"
          @update:content="skillForm.content = $event"
          @update:files="skillForm.files = $event"
        />
        <div class="sk-actions">
          <n-button size="small" quaternary @click="skillModalShow = false">{{ skillReadonly ? t('skillsPanel2.close') : t('skillsPanel2.cancel') }}</n-button>
          <n-button v-if="!skillReadonly" size="small" type="primary" :disabled="!skillForm.name.trim()" @click="saveSkill">{{ t('skillsPanel2.save') }}</n-button>
        </div>
      </div>
    </n-modal>
  </div>
</template>

<style scoped>
/* Tab 条样式已上收到 components/ui/SectionTabs.vue,此处只保留面板级间距 */
.skill-tabs { margin-bottom: 12px; }
.skill-pane { padding-top: 2px; }
.market-pane :deep(.manage-page) { padding: 0; background: transparent; height: auto; overflow: visible; }

.user-skills { padding: 8px 0; }
.us-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.us-title { font-size: 13px; font-weight: 600; color: var(--text-2); }
.us-empty { font-size: 12px; color: var(--text-3); padding: 12px 2px; }
.us-card {
  display: flex; align-items: center; gap: 10px; padding: 8px 10px;
  height: 80px; box-sizing: border-box; overflow: hidden;
  border: 1px solid var(--border-soft); border-radius: 10px; margin-bottom: 8px;
  background: var(--bg-elev); transition: border-color .15s, box-shadow .15s;
}
.us-card:hover { border-color: var(--accent); box-shadow: 0 2px 10px rgba(0,0,0,.05); }
.us-card-icon {
  width: 40px; height: 40px; flex-shrink: 0; border-radius: 10px;
  display: flex; align-items: center; justify-content: center; font-size: 20px;
  background: var(--fill-1); border: 1px solid var(--border-soft);
}
.us-card-body { flex: 1; min-width: 0; display: flex; flex-direction: column; justify-content: center; gap: 3px; }
.us-card-name { font-size: 13.5px; font-weight: 600; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.us-card-tags { display: flex; gap: 5px; white-space: nowrap; overflow: hidden; }
.us-tag { font-size: 10.5px; padding: 1px 6px; border-radius: 6px; flex-shrink: 0; }
.us-tag.pub { background: rgba(82,141,245,.15); color: var(--accent-text); }
.us-tag.pri { background: var(--surface-hover); color: var(--text-3); }
.us-tag.own { background: rgba(88,196,155,.12); color: var(--ok); }
.us-tag.inst { background: #f59e0b22; color: #f59e0b; }
.us-desc { font-size: 11.5px; color: var(--text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.us-actions { display: flex; flex-direction: column; gap: 6px; flex-shrink: 0; }
.us-act {
  font-size: 11px; line-height: 1; padding: 5px 12px; border-radius: 999px; cursor: pointer;
  border: 1px solid var(--border-soft); background: var(--surface); color: var(--text-2);
  transition: all .15s; white-space: nowrap;
}
.us-act:hover { border-color: var(--accent-text); color: var(--accent-text); background: var(--accent-soft); }
.us-act.danger:hover { border-color: var(--error, #ec5962); color: var(--error, #ec5962); background: rgba(236,89,98,.08); }
.us-act:disabled { opacity: .4; cursor: not-allowed; }
.us-act:disabled:hover { border-color: var(--border-soft); color: var(--text-2); background: var(--surface); }

/* 图标选择器已上收到 components/ui/IconPicker.vue */

.sk-form { display: flex; flex-direction: column; gap: 8px; }
.sk-row { display: flex; flex-direction: column; gap: 4px; }
.sk-label { font-size: 12px; color: var(--text-2); }
.sk-form .skill-editor { margin-top: 2px; }
.sk-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }

/* 内置技能列表(只读,默认折叠) */
.builtin-collapse { margin-bottom: 2px; }
.builtin-note { font-size: 11px; color: var(--text-3); margin-left: 8px; }
.builtin-empty { color: var(--text-3); font-size: 12px; padding: 8px 0; }
.builtin-item {
  display: flex; flex-direction: column; gap: 2px;
  padding: 8px 10px; border-radius: var(--radius-sm);
  background: var(--surface); border: 1px solid var(--border-soft);
}
.builtin-name { font-size: 12px; font-weight: 600; color: var(--accent); }
.builtin-desc {
  font-size: 12px; color: var(--text-2); line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
</style>
