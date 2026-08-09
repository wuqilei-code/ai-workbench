<script setup lang="ts">
import { computed } from 'vue'
import { darkTheme, lightTheme, dateZhCN, zhCN } from 'naive-ui'
import type { GlobalThemeOverrides } from 'naive-ui'
import { useThemeStore } from '@/stores/theme'

const theme = useThemeStore()
const isDark = computed(() => theme.mode === 'dark')

/** 暗色主题覆盖:柔和现代暗色(低饱和靛蓝主色、大圆角、细腻边框) */
const darkOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#6b8afd',
    primaryColorHover: '#7d9bff',
    primaryColorPressed: '#5a7aee',
    primaryColorSuppl: '#6b8afd',
    infoColor: '#6b8afd',
    successColor: '#58c49b',
    warningColor: '#e8b26b',
    errorColor: '#e47a86',
    borderRadius: '10px',
    borderRadiusSmall: '8px',
    fontSize: '14px',
    bodyColor: '#14161a',
    cardColor: '#1d2026',
    modalColor: '#1d2026',
    popoverColor: '#21242b',
    dividerColor: 'rgba(255,255,255,0.08)',
    borderColor: 'rgba(255,255,255,0.1)',
    hoverColor: 'rgba(255,255,255,0.06)',
    textColorBase: '#e8e9ec',
    textColor1: '#eceef1',
    textColor2: '#c2c6cd',
    textColor3: '#8a909a',
    textColorDisabled: '#5c616b',
    inputColor: '#20242b',
    inputColorDisabled: '#1a1d22',
    actionColor: '#23272e',
    tableColor: '#1d2026',
    tableColorHover: '#22262d',
    tableColorStriped: '#191c21',
    iconColor: '#9aa1ab',
    iconColorHover: '#c6cad0',
  },
}

/** 浅色主题覆盖:同色系,亮背景、深色文字 */
const lightOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#5a6fd8',
    primaryColorHover: '#6f85e8',
    primaryColorPressed: '#4a5fc2',
    primaryColorSuppl: '#5a6fd8',
    infoColor: '#5a6fd8',
    successColor: '#2e9e6b',
    warningColor: '#d99a3d',
    errorColor: '#d25563',
    borderRadius: '10px',
    borderRadiusSmall: '8px',
    fontSize: '14px',
    bodyColor: '#f5f6fa',
    cardColor: '#ffffff',
    modalColor: '#ffffff',
    popoverColor: '#ffffff',
    dividerColor: 'rgba(20,22,26,0.08)',
    borderColor: 'rgba(20,22,26,0.12)',
    hoverColor: 'rgba(20,22,26,0.05)',
    textColorBase: '#1a1d24',
    textColor1: '#1a1d24',
    textColor2: '#434a57',
    textColor3: '#7a818d',
    textColorDisabled: '#aab0ba',
    inputColor: '#ffffff',
    inputColorDisabled: '#eef0f4',
    actionColor: '#f0f2f7',
    tableColor: '#ffffff',
    tableColorHover: '#f4f6fa',
    tableColorStriped: '#f8f9fc',
    iconColor: '#6a7180',
    iconColorHover: '#3a4150',
  },
}
</script>

<template>
  <n-config-provider
    :theme="isDark ? darkTheme : lightTheme"
    :theme-overrides="isDark ? darkOverrides : lightOverrides"
    :locale="zhCN"
    :date-locale="dateZhCN"
  >
    <n-message-provider>
      <n-dialog-provider>
        <n-notification-provider>
          <router-view />
        </n-notification-provider>
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>

<style>
/* ===== 全局设计令牌:两套主题 ===== */
/* 暗色(默认) */
:root,
:root[data-theme='dark'] {
  --bg: #14161a;
  --bg-elev: #1d2026;
  --surface: #20242b;
  --surface-hover: #262b33;
  --card-bg: #2b3445;
  --border: rgba(255, 255, 255, 0.09);
  --border-soft: rgba(255, 255, 255, 0.06);
  --text: #e8e9ec;
  --text-2: #aeb3bb;
  --text-3: #7c828c;
  --text-4: #5a6478;
  --accent: #6b8afd;
  --accent-soft: rgba(107, 138, 253, 0.12);
  --accent-text: #a9bcff;
  --ok: #58c49b;
  --warn: #e8b26b;
  --err: #e47a86;
  --radius: 12px;
  --radius-sm: 8px;
  --shadow: 0 6px 24px rgba(0, 0, 0, 0.35);
}

/* 浅色 */
:root[data-theme='light'] {
  --bg: #f5f6fa;
  --bg-elev: #ffffff;
  --surface: #ffffff;
  --surface-hover: #f0f2f7;
  --card-bg: #e8ecf5;
  --border: rgba(20, 22, 26, 0.12);
  --border-soft: rgba(20, 22, 26, 0.08);
  --text: #1a1d24;
  --text-2: #434a57;
  --text-3: #7a818d;
  --text-4: #aab0ba;
  --accent: #5a6fd8;
  --accent-soft: rgba(90, 111, 216, 0.1);
  --accent-text: #4a5fc2;
  --ok: #2e9e6b;
  --warn: #d99a3d;
  --err: #d25563;
  --radius: 12px;
  --radius-sm: 8px;
  --shadow: 0 6px 24px rgba(30, 40, 90, 0.12);
}

/* 全局滚动条:柔和、细窄、半透明(随主题) */
* {
  scrollbar-width: thin;
}
:root[data-theme='dark'] * {
  scrollbar-color: rgba(255, 255, 255, 0.14) transparent;
}
:root[data-theme='light'] * {
  scrollbar-color: rgba(20, 22, 26, 0.18) transparent;
}
:root[data-theme='dark'] ::-webkit-scrollbar-track {
  background: transparent;
}
:root[data-theme='light'] ::-webkit-scrollbar-track {
  background: transparent;
}
:root[data-theme='dark'] ::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.14);
  border-radius: 4px;
}
:root[data-theme='light'] ::-webkit-scrollbar-thumb {
  background: rgba(20, 22, 26, 0.18);
  border-radius: 4px;
}
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}
::-webkit-scrollbar-thumb:hover {
  background: rgba(127, 139, 160, 0.4);
}
::-webkit-scrollbar-corner {
  background: transparent;
}

/* ===== Markdown 代码块:深色底 + 右上角复制按钮 ===== */
.code-block { position: relative; }
.code-block pre.hljs { margin: 6px 0; border-radius: var(--radius-sm); }
.code-copy-btn {
  position: absolute; top: 6px; right: 8px; z-index: 1;
  font-size: 11px; line-height: 1;
  color: #9aa1ab; background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 6px; padding: 3px 8px; cursor: pointer;
  opacity: 0; transition: opacity .15s, background .15s, color .15s;
}
.code-block:hover .code-copy-btn { opacity: 1; }
.code-copy-btn:hover { color: #fff; background: rgba(255, 255, 255, 0.16); }
</style>
