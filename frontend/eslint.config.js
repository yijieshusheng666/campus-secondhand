import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import prettier from 'eslint-config-prettier'
import globals from 'globals'

/**
 * ESLint 扁平配置（ESLint 9）。
 *
 * 为什么现在才加：这个仓库此前没有任何 lint 与格式化工具，直接后果是
 * `<img  loading="lazy"v-if=...>` 这类**两个属性之间缺空格**的写法能长期存活
 * （浏览器能容错解析，所以没人发现），以及 5 份逐字相同的 no-op 函数、
 * 12 处恒为假的 `if (!user)`。工具不是为了好看，是为了让这类问题在提交前就被拦下。
 *
 * 规则刻意选得克制：
 * - 用 vue3-essential 而不是 strongly-recommended，后者夹带大量格式偏好，
 *   与 Prettier 的职责重叠，一起开会互相打架；
 * - 末位放 eslint-config-prettier，关掉所有与 Prettier 冲突的格式规则。
 */
export default [
  {
    ignores: ['dist/**', 'node_modules/**', 'public/**', '*.min.js', '_dev.log']
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  prettier,
  {
    files: ['**/*.{js,mjs,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser }
    },
    rules: {
      // 未使用的变量只警告：清理是渐进的，但要让新引入的立刻可见
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_', caughtErrors: 'none' }],
      // 允许空的 catch —— 本项目多处刻意「忽略失败、不影响主流程」，
      // 但要求写注释说明为什么（见 no-empty 的注释约定，无法用规则强制，靠 review）
      'no-empty': ['error', { allowEmptyCatch: true }],
      // 页面组件用单词名（Home / Login / Orders…）是刻意选择，不必强制多词
      'vue/multi-word-component-names': 'off'
    }
  }
]
