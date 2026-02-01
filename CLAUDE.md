# Agent Rules:

## Использование субагентов

Для выполнения сложных задач необходимо активно использовать субагентов (Task tool).

### Когда и каких субагентов использовать

#### 1. Исследование кодовой базы
- **`Explore`** - ИСПОЛЬЗУЙТЕ ПЕРВЫМ для исследования:
  - Поиск файлов по паттернам (`src/**/*.kt`, `app/src/**/*Test.kt`)
  - Поиск кода по ключевым словам
  - Ответы на вопросы о кодовой базе ("как работает X?", "где находится Y?")
  - Уровни детализации: `"quick"`, `"medium"`, `"very thorough"`

#### 2. Языковые субагенты (voltagent-lang:*)
Используйте для работы с конкретным языком:

- **`kotlin-specialist`** - Kotlin (корутины, KMP, Android)
- **`java-architect`** - Java enterprise, Spring экосистема
- **`typescript-pro`** - TypeScript с продвинутой типизацией
- **`javascript-pro`** - ES2023+, Node.js, browser APIs
- **`python-pro`** - Python 3.11+ (type safety, async, data science)
- **`golang-pro`** - Go microservices, high-performance systems
- **`rust-engineer`** - Rust (systems programming, memory safety)
- **`cpp-pro`** - Modern C++20/23, template metaprogramming
- **`csharp-developer`** / **`dotnet-core-expert`** - .NET/C# development
- **`php-pro`** / **`laravel-specialist`** - PHP/Laravel
- **`rails-expert`** - Ruby on Rails 8.1
- **`swift-expert`** - Swift 5.9+, iOS/macOS development
- **`flutter-expert`** - Flutter 3+, cross-platform mobile
- **`react-specialist`** - React 18+ with modern patterns
- **`vue-expert`** - Vue 3 with Composition API
- **`nextjs-developer`** - Next.js 14+ with App Router
- **`angular-architect`** - Angular 15+ enterprise apps
- **`sql-pro`** - Complex query optimization, DB design

#### 3. Инфраструктурные субагенты (voltagent-infra:*)
Используйте для DevOps/CI/CD/инфраструктуры:

- **`devops-engineer`** - CI/CD, automation, bridging dev/ops
- **`kubernetes-specialist`** - K8s deployments, orchestration
- **`terraform-engineer`** - Infrastructure as Code
- **`cloud-architect`** - Multi-cloud strategies, architecture
- **`security-engineer`** - DevSecOps, vulnerability management
- **`platform-engineer`** - Internal developer platforms
- **`deployment-engineer`** - Release automation, deployments
- **`sre-engineer`** - SLOs, monitoring, reliability
- **`database-administrator`** - DB administration, HA, performance
- **`incident-responder`** - Incident management, root cause analysis

#### 4. Универсальные субагенты

- **`Plan`** - Планирование разработки:
  - Анализ требований перед реализацией
  - Разработка плана реализации
  - Уровни: `"quick"`, `"medium"`, `"very thorough"`

- **`general-purpose`** - Сложные многошаговые задачи:
  - Исследование сложных вопросов
  - Задачи требующие нескольких шагов
  - Доступ ко всем инструментам

- **`claude-code-guide`** - Вопросы о Claude Code:
  - Как работает Claude Code
  - Использование hooks, slash commands, MCP servers
  - Claude Agent SDK архитектура
  - **ВАЖНО:** Проверьте, нет ли уже запущенного агента перед созданием нового

#### 5. Когда НЕ использовать субагентов

❌ **Для чтения конкретного файла** → используйте **Read**
❌ **Для поиска файла по имени** → используйте **Glob**
❌ **Для поиска в 2-3 файлах** → используйте **Read**
❌ **Для простых операций** → выполняйте самостоятельно

#### 6. Принципы использования

✅ **Запускайте агентов параллельно** - одно сообщение с несколькими Task вызовами
✅ **Выбирайте специализированного агента** под технологию
✅ **Для CompressPhotoFast** (Kotlin/Android) → `kotlin-specialist`
✅ **Сначала Explore** для исследования кода, затем специализированный агент

## Обязательные файлы для чтения

При работе с проектом CompressPhotoFast необходимо обращаться к следующим файлам:

### 1. Правила разработки
**Файл:** [`.kilocode/rules/rules.md`](.kilocode/rules/rules.md)

Содержит основные правила разработки:
- Настройки (язык общения, ОС)
- Требования к сборке (обязательная сборка после каждого изменения кода)
- Планирование разработки с указанием необходимости сборки

### 2. Memory Bank
**Папка:** [`.kilocode/rules/memory-bank/`](.kilocode/rules/memory-bank/)

Содержит документацию проекта:
- [`brief.md`](.kilocode/rules/memory-bank/brief.md) - краткое описание проекта
- [`product.md`](.kilocode/rules/memory-bank/product.md) - описание продукта и функций
- [`context.md`](.kilocode/rules/memory-bank/context.md) - текущий контекст и последние изменения
- [`architecture.md`](.kilocode/rules/memory-bank/architecture.md) - архитектура приложения
- [`tech.md`](.kilocode/rules/memory-bank/tech.md) - технологический стек
- [`memory-bank-instructions.md`](.kilocode/rules/memory-bank/memory-bank-instructions.md) - инструкции по работе с Memory Bank

**ВАЖНО:** В начале КАЖДОЙ задачи необходимо читать ВСЕ файлы из Memory Bank.