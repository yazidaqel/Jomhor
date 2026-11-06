import js from '@eslint/js'
import globals from 'globals'
import nextPlugin from '@next/eslint-plugin-next'
import reactHooks from 'eslint-plugin-react-hooks'
import tseslint from 'typescript-eslint'
import i18next from 'eslint-plugin-i18next'
import { defineConfig, globalIgnores } from 'eslint/config'

// Attributes that carry user-visible text (same guard as web/backoffice).
const TEXT_ATTRS = '(placeholder|alt|title|label|aria-(label|description|roledescription|valuetext|placeholder|braillelabel|brailleroledescription))'
const TEXT = ':matches(Literal[value=/[^ ]/], TemplateLiteral)'

export default defineConfig([
  globalIgnores(['.next', 'next-env.d.ts']),
  {
    files: ['**/*.{ts,tsx,mts,mjs}'],
    extends: [js.configs.recommended, tseslint.configs.recommended, reactHooks.configs.flat.recommended, nextPlugin.configs['core-web-vitals']],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
  },
  // No hard-coded user-visible strings: JSX text, strings in JSX expressions and text-bearing attributes come from
  // the dictionary (lib/i18n).
  {
    files: ['**/*.tsx'],
    ignores: ['**/*.test.tsx'],
    plugins: { i18next },
    rules: {
      'i18next/no-literal-string': [
        'error',
        { mode: 'jsx-only', 'should-validate-template': true, 'jsx-attributes': { include: [TEXT_ATTRS], exclude: [] } },
      ],
      'no-restricted-syntax': [
        'error',
        ...['', ' > JSXExpressionContainer', ' > JSXExpressionContainer > :matches(ConditionalExpression, LogicalExpression)'].map(
          (path) => ({
            selector: `JSXAttribute[name.name=/^${TEXT_ATTRS}$/]${path} > ${TEXT}`,
            message: 'User-visible text comes from the dictionary (lib/i18n).',
          }),
        ),
      ],
    },
  },
])
