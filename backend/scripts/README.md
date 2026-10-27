# Backend image generation

Run `python3 backend/scripts/render-dockerfiles.py` from the repository root after changing the shared image template. Run it with `--check` for a read-only drift check. Commit the renderer and all generated files together.

The renderer preserves all existing build entry points:

- `backend/Dockerfile`: context `backend/`, source build, one target per application.
- `backend/Dockerfile.prebuilt`: context `backend/`, packaged JARs, one target per application.
- `infra/docker/<service>/Dockerfile`: repository-root context, service source build, default `runtime` target used by CD.

All paths use the same pinned multi-platform Temurin 25 JDK/JRE image indexes, Boot extracted layers, user 1001, health tooling, JVM flags and AOT cache creation. Refresh both digests deliberately when updating Temurin; training and execution must use the same JRE. No workflow change is required.

`build-support` supplies an isolated `AotCacheTraining` main on the actual application's extracted classpath. Its `aot-training` context has no component scanning, imports, auto-configuration, migrations, schedulers, consumers, provider clients or application beans. Training also ignores application configuration locations and runs with BuildKit networking disabled. `spring.context.exit=onRefresh` alone would not make normal service startup safe for training.

This warms core Spring classes only. It does not pre-initialize business beans and makes no measured startup-time promise. The resulting cache is specific to the application files, JRE, architecture and compact-header setting; rebuild it with every image. Runtime configuration stays outside the image. The runtime starts the actual application main from the extracted thin JAR with `-XX:AOTCache=application.aot`.

References: [Boot AOT cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html), [Boot container layers](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html).
