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
    primaryColor: '#8B5CF6',
    primaryColorHover: '#A78BFA',
    primaryColorPressed: '#7C3AED',
    primaryColorSuppl: '#8B5CF6',
    infoColor: '#8B5CF6',
    successColor: '#10B981',
    warningColor: '#F59E0B',
    errorColor: '#EF4444',
    borderRadius: '8px',
    borderRadiusSmall: '6px',
    fontSize: '14px',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
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
    primaryColor: '#8B5CF6',
    primaryColorHover: '#7C3AED',
    primaryColorPressed: '#6D28D9',
    primaryColorSuppl: '#8B5CF6',
    infoColor: '#8B5CF6',
    successColor: '#10B981',
    warningColor: '#F59E0B',
    errorColor: '#EF4444',
    borderRadius: '8px',
    borderRadiusSmall: '6px',
    fontSize: '14px',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
    bodyColor: '#FFFFFF',
    cardColor: '#FFFFFF',
    modalColor: '#FFFFFF',
    popoverColor: '#FFFFFF',
    dividerColor: 'rgba(0,0,0,0.06)',
    borderColor: 'rgba(0,0,0,0.08)',
    hoverColor: 'rgba(0,0,0,0.03)',
    textColorBase: '#1a1a1a',
    textColor1: '#1a1a1a',
    textColor2: '#666666',
    textColor3: '#999999',
    textColorDisabled: '#cccccc',
    inputColor: '#FFFFFF',
    inputColorDisabled: '#f5f5f5',
    actionColor: '#f5f5f5',
    tableColor: '#FFFFFF',
    tableColorHover: '#f5f5f5',
    tableColorStriped: '#fafafa',
    iconColor: '#666666',
    iconColorHover: '#1a1a1a',
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
  --accent: #8B5CF6;
  --accent-soft: rgba(139, 92, 246, 0.15);
  --accent-text: #A78BFA;
  --ok: #58c49b;
  --warn: #e8b26b;
  --err: #e47a86;
  --radius: 8px;
  --radius-sm: 6px;
  --shadow: 0 6px 24px rgba(0, 0, 0, 0.35);
  /* 氛围光晕(背景装饰) */
  --glow-1: rgba(139, 92, 246, 0.10);
  --glow-2: rgba(139, 92, 246, 0.06);
  --sidebar-bg: #1d2026;
  --chat-bg: #14161a;
  --input-bg: #20242b;
  --input-border: rgba(255, 255, 255, 0.12);
  --user-bubble-bg: #2b3445;
  --ai-bubble-bg: #20242b;
  /* 登录/注册页两版主题 */
  --auth-bg: linear-gradient(150deg, #101218 0%, #161924 45%, #1b1e2e 100%);
  --auth-shell-bg: rgba(255, 255, 255, 0.035);
  --auth-shell-border: rgba(255, 255, 255, 0.08);
  --auth-shell-shadow: 0 24px 80px rgba(0, 0, 0, 0.45);
  --auth-panel-bg: rgba(13, 15, 20, 0.45);
  --auth-panel-border: rgba(255, 255, 255, 0.06);
  --auth-card-bg: rgba(255, 255, 255, 0.04);
  --auth-card-border: rgba(255, 255, 255, 0.06);
  --auth-glow-1: rgba(139, 92, 246, 0.16);
  --auth-glow-2: rgba(139, 92, 246, 0.10);
  --auth-grid: rgba(255, 255, 255, 0.025);
}

/* 浅色 */
:root[data-theme='light'] {
  --bg: #FFFFFF;
  --bg-elev: #FFFFFF;
  --surface: #FFFFFF;
  --surface-hover: #f5f5f5;
  --card-bg: #f5f5f5;
  --border: rgba(0, 0, 0, 0.08);
  --border-soft: rgba(0, 0, 0, 0.06);
  --text: #1a1a1a;
  --text-2: #666666;
  --text-3: #999999;
  --text-4: #cccccc;
  --accent: #8B5CF6;
  --accent-soft: rgba(139, 92, 246, 0.08);
  --accent-text: #7C3AED;
  --ok: #10B981;
  --warn: #F59E0B;
  --err: #EF4444;
  --radius: 8px;
  --radius-sm: 6px;
  --shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  --glow-1: rgba(139, 92, 246, 0.04);
  --glow-2: rgba(139, 92, 246, 0.02);
  --sidebar-bg: #f7f8fa;
  --chat-bg: #FFFFFF;
  --input-bg: #FFFFFF;
  --input-border: rgba(0, 0, 0, 0.12);
  --user-bubble-bg: #f0f0f0;
  --ai-bubble-bg: #f5f5f5;
  /* 登录/注册页两版主题 */
  --auth-bg: linear-gradient(150deg, #f6f7fb 0%, #f1f2f9 45%, #eceef7 100%);
  --auth-shell-bg: rgba(255, 255, 255, 0.75);
  --auth-shell-border: rgba(0, 0, 0, 0.08);
  --auth-shell-shadow: 0 24px 80px rgba(80, 70, 120, 0.18);
  --auth-panel-bg: rgba(255, 255, 255, 0.65);
  --auth-panel-border: rgba(0, 0, 0, 0.06);
  --auth-card-bg: rgba(255, 255, 255, 0.72);
  --auth-card-border: rgba(0, 0, 0, 0.06);
  --auth-glow-1: rgba(139, 92, 246, 0.14);
  --auth-glow-2: rgba(139, 92, 246, 0.08);
  --auth-grid: rgba(0, 0, 0, 0.04);
}

/* 全局背景氛围:纯色背景,干净简洁 */
html, body {
  height: 100%;
}
body {
  margin: 0;
  background: var(--bg);
  -webkit-font-smoothing: antialiased;
  text-rendering: optimizeLegibility;
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

/* 全局选中颜色跟随主题 */
::selection {
  background: var(--accent-soft);
  color: var(--text);
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

/* 浅色主题:hljs 代码块适配浅色底(覆盖 github-dark 默认) */
:root[data-theme='light'] pre.hljs,
:root[data-theme='light'] .preview-code.hljs {
  background: #f6f8fa; color: #24292e;
}
:root[data-theme='light'] .hljs-keyword,
:root[data-theme='light'] .hljs-selector-tag,
:root[data-theme='light'] .hljs-literal { color: #cf222e; }
:root[data-theme='light'] .hljs-string,
:root[data-theme='light'] .hljs-regexp,
:root[data-theme='light'] .hljs-attribute { color: #0a3069; }
:root[data-theme='light'] .hljs-number,
:root[data-theme='light'] .hljs-symbol { color: #0550ae; }
:root[data-theme='light'] .hljs-title,
:root[data-theme='light'] .hljs-title.function_,
:root[data-theme='light'] .hljs-title.class_ { color: #8250df; }
:root[data-theme='light'] .hljs-comment,
:root[data-theme='light'] .hljs-quote { color: #6e7781; font-style: italic; }
:root[data-theme='light'] .hljs-built_in { color: #953800; }
</style>
