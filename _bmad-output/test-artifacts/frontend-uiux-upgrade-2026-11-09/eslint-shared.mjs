// Apply the existing customer Vue/TypeScript rules to shared sources from the
// frontend root. Loading the config in its own cwd preserves its Oxlint lookup.
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const frontend = path.join(root, 'frontend')
const modules = createRequire(path.join(frontend, 'package.json'))
const { createJiti } = modules('jiti')
process.chdir(path.join(frontend, 'customer-website'))
const config = await createJiti(import.meta.url).import(path.join(process.cwd(), 'eslint.config.ts'), { default: true })
const { ESLint } = modules('eslint')
const eslint = new ESLint({ cwd: frontend, overrideConfigFile: true, overrideConfig: config, fix: false })
const files = ['shared/src/index.ts', 'shared/src/useMediaQuery.ts'].map(file => path.join(frontend, file))
console.log(JSON.stringify({ cwd: frontend, config: 'customer-website/eslint.config.ts (existing rules, no overrides)', fix: false, files }))
const results = await eslint.lintFiles(files)
console.log((await eslint.loadFormatter('stylish')).format(results))
console.log(JSON.stringify(results.map(result => ({ file: result.filePath, errors: result.errorCount, warnings: result.warningCount }))))
process.exitCode = results.some(result => result.errorCount || result.warningCount) ? 1 : 0
