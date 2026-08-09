<script setup lang="ts">
import { darkTheme, dateZhCN, zhCN } from 'naive-ui'
import type { GlobalThemeOverrides } from 'naive-ui'

/** 柔和现代暗色主题覆盖:低饱和靛蓝主色、大圆角、细腻边框 */
const themeOverrides: GlobalThemeOverrides = {
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
</script>

<template>
  <n-config-provider :theme="darkTheme" :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
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
/* ===== 全局设计令牌:柔和现代暗色 ===== */
:root {
  --bg: #14161a;
  --bg-elev: #1d2026;
  --surface: #20242b;
  --surface-hover: #262b33;
  /* 卡片表面:明显亮于背景、偏冷蓝灰,与深色页面底色拉开层次 */
  --card-bg: #2b3445;
  --border: rgba(255, 255, 255, 0.09);
  --border-soft: rgba(255, 255, 255, 0.06);
  --text: #e8e9ec;
  --text-2: #aeb3bb;
  --text-3: #7c828c;
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

/* 全局滚动条:柔和、细窄、半透明 */
* {
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.14) transparent;
}
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}
::-webkit-scrollbar-track {
  background: transparent;
}
::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.14);
  border-radius: 4px;
}
::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.24);
}
::-webkit-scrollbar-corner {
  background: transparent;
}
</style>
