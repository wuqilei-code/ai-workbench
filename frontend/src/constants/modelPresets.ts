/**
 * 常用模型厂商预设:在「模型配置 → 添加 Provider」中选中后,自动带出 Base URL 等默认配置,
 * 通常只需再填写 API Key;Base URL 可改为内网 / 自建网关地址。
 *
 * 字段与后端 provider_config 对应:providerId / name / baseUrl / npm / envVarName。
 * 均为 OpenAI 兼容端点,故 npm 统一为 @ai-sdk/openai-compatible。
 */
export interface ModelPreset {
  /** providerId(逻辑标识) */
  id: string
  /** 展示名 */
  name: string
  /** 默认 Base URL(OpenAI 兼容端点);留空表示需自行填写 */
  baseUrl: string
  /** AI SDK npm 包名 */
  npm: string
  /** API Key 的环境变量名 */
  envVarName: string
  /** 图标 emoji */
  icon: string
  /** 备注(可选) */
  note?: string
}

const OPENAI_COMPAT = '@ai-sdk/openai-compatible'

export const MODEL_PRESETS: ModelPreset[] = [
  { id: 'deepseek', name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', npm: OPENAI_COMPAT, envVarName: 'DEEPSEEK_API_KEY', icon: '🐋' },
  { id: 'openai', name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', npm: OPENAI_COMPAT, envVarName: 'OPENAI_API_KEY', icon: '🟢' },
  { id: 'qwen', name: '通义千问(阿里云百炼)', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', npm: OPENAI_COMPAT, envVarName: 'DASHSCOPE_API_KEY', icon: '☁️' },
  { id: 'zhipu', name: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', npm: OPENAI_COMPAT, envVarName: 'ZHIPUAI_API_KEY', icon: '🧠' },
  { id: 'moonshot', name: '月之暗面 Kimi', baseUrl: 'https://api.moonshot.cn/v1', npm: OPENAI_COMPAT, envVarName: 'MOONSHOT_API_KEY', icon: '🌙' },
  { id: 'doubao', name: '火山方舟(豆包)', baseUrl: 'https://ark.cn-beijing.volces.com/api/v3', npm: OPENAI_COMPAT, envVarName: 'ARK_API_KEY', icon: '🌋' },
  { id: 'qianfan', name: '百度千帆(文心)', baseUrl: 'https://qianfan.baidubce.com/v2', npm: OPENAI_COMPAT, envVarName: 'QIANFAN_API_KEY', icon: '🐻' },
  { id: 'hunyuan', name: '腾讯混元', baseUrl: 'https://api.hunyuan.cloud.tencent.com/v1', npm: OPENAI_COMPAT, envVarName: 'HUNYUAN_API_KEY', icon: '🐧' },
  { id: 'siliconflow', name: '硅基流动 SiliconFlow', baseUrl: 'https://api.siliconflow.cn/v1', npm: OPENAI_COMPAT, envVarName: 'SILICONFLOW_API_KEY', icon: '🌊' },
  { id: 'minimax', name: 'MiniMax', baseUrl: 'https://api.minimax.chat/v1', npm: OPENAI_COMPAT, envVarName: 'MINIMAX_API_KEY', icon: '🎯' },
  { id: 'stepfun', name: '阶跃星辰 StepFun', baseUrl: 'https://api.stepfun.com/v1', npm: OPENAI_COMPAT, envVarName: 'STEPFUN_API_KEY', icon: '🪜' },
  { id: 'baichuan', name: '百川 Baichuan', baseUrl: 'https://api.baichuan-ai.com/v1', npm: OPENAI_COMPAT, envVarName: 'BAICHUAN_API_KEY', icon: '🏞️' },
  { id: 'yi', name: '零一万物 Yi', baseUrl: 'https://api.lingyiwanwu.com/v1', npm: OPENAI_COMPAT, envVarName: 'YI_API_KEY', icon: '1️⃣' },
  { id: 'spark', name: '讯飞星火', baseUrl: 'https://spark-api-open.xf-yun.com/v1', npm: OPENAI_COMPAT, envVarName: 'SPARK_API_KEY', icon: '✨' },
  { id: 'openrouter', name: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1', npm: OPENAI_COMPAT, envVarName: 'OPENROUTER_API_KEY', icon: '🔀' },
  { id: 'groq', name: 'Groq', baseUrl: 'https://api.groq.com/openai/v1', npm: OPENAI_COMPAT, envVarName: 'GROQ_API_KEY', icon: '⚡' },
  { id: 'google', name: 'Google Gemini', baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai/', npm: OPENAI_COMPAT, envVarName: 'GEMINI_API_KEY', icon: '💎' },
  { id: 'ollama', name: 'Ollama(本地)', baseUrl: 'http://host.docker.internal:11434/v1', npm: OPENAI_COMPAT, envVarName: 'OLLAMA_API_KEY', icon: '🦙', note: '本地部署无需真实 Key,可随意填写' },
  { id: 'custom', name: '自定义(OpenAI 兼容)', baseUrl: '', npm: OPENAI_COMPAT, envVarName: '', icon: '🔧', note: '填写内网 / 自建网关地址' },
]
