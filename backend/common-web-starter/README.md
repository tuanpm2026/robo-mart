# Common web starter

Depends on common-core and servlet/observability infrastructure, never JPA/Flyway. Boot's AutoConfiguration.imports registers Jackson customization, servlet exception advice and audit support. No component scan is performed. Servlet advice backs off when the application provides its own bean; optional Spring Security advice/audit are guarded by classpath conditions. A missing tracer uses Micrometer's no-op tracer. Domain services scan only themselves and shared security, so common configuration is loaded exclusively through Boot auto-configuration.

The transitional common-lib artifact is a dependency-only facade. Existing CI explicitly selects it, so its test source points to this module's tests; this avoids changing workflow files while still exercising starter contracts in that CI job. The owning module has its own tests and normal 80% coverage gate in reactor verification. Retire the facade and CI selector together in a future workflow update.
