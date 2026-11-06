import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import i18next from 'eslint-plugin-i18next'
import { defineConfig, globalIgnores } from 'eslint/config'

// Attributes that carry user-visible text.
const TEXT_ATTRS = '(placeholder|alt|title|label|aria-(label|description|roledescription|valuetext|placeholder|braillelabel|brailleroledescription))'
// A non-blank string literal or a template literal, as an attribute value.
const TEXT = ':matches(Literal[value=/[^ ]/], TemplateLiteral)'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: { globals: globals.browser },
  },
  // No hard-coded user-visible strings: JSX text, strings in JSX expressions, and text-bearing attributes
  // (any tag) must come from t(). Other props (variant, to, id, aria-describedby, ...) are not copy.
  {
    files: ['src/**/*.tsx'],
    ignores: ['src/components/ui/**', 'src/**/*.test.tsx'],
    plugins: { i18next },
    rules: {
      'i18next/no-literal-string': [
        'error',
        {
          mode: 'jsx-only',
          'should-validate-template': true,
          'jsx-attributes': { include: [TEXT_ATTRS], exclude: [] },
          callees: { exclude: ['t', 'i18n(ext)?', 'register'] },
        },
      ],
      // The plugin skips aria-* other than aria-label on native tags; cover them here.
      'no-restricted-syntax': [
        'error',
        ...['', ' > JSXExpressionContainer', ' > JSXExpressionContainer > :matches(ConditionalExpression, LogicalExpression)'].map(
          (path) => ({
            selector: `JSXAttribute[name.name=/^${TEXT_ATTRS}$/]${path} > ${TEXT}`,
            message: 'User-visible text goes through t() (src/i18n).',
          }),
        ),
      ],
    },
  },
  // shadcn components export their cva variants next to the component.
  { files: ['src/components/ui/**'], rules: { 'react-refresh/only-export-components': 'off' } },
])
