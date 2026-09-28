#!/usr/bin/env sh
set -eu

# Run only synthetic fixtures in a nonroot container. The host repository is not
# mounted: stage the packaged jar and this benchmark script alone.
mode="${1:-offline}"
if [ "$#" -gt 0 ]; then shift; fi
case "$mode" in
  bootstrap) network=bridge ;;
  offline) network=none ;;
  *) echo 'usage: benchmark-docker.sh [bootstrap|offline] [benchmark options]' >&2; exit 2 ;;
esac

jar="target/mcp-server-jvm-build-tools.jar"
image="${BENCHMARK_IMAGE:-jvm-build-tools:benchmark}"
volume="${BENCHMARK_CACHE_VOLUME:-jvm-benchmark-public-artifacts}"
if [ ! -f "$jar" ]; then
  echo 'Build the packaged jar first.' >&2
  exit 2
fi

# Networked bootstrap sees only a dedicated empty/public Maven volume. An
# explicitly selected host repository is mounted read-only in offline mode only.
maven_volume="${volume}-maven"
maven_mount="type=volume,src=$maven_volume,dst=/m2"
if [ "$mode" = offline ] && [ -n "${BENCHMARK_OFFLINE_MAVEN_REPOSITORY:-}" ]; then
  if [ ! -d "$BENCHMARK_OFFLINE_MAVEN_REPOSITORY" ]; then
    echo 'BENCHMARK_OFFLINE_MAVEN_REPOSITORY must be an existing directory.' >&2
    exit 2
  fi
  maven_mount="type=bind,src=$BENCHMARK_OFFLINE_MAVEN_REPOSITORY,dst=/m2,readonly"
fi

stage="$(mktemp -d "${TMPDIR:-/tmp}/jvm-benchmark.XXXXXXXX")"
trap 'rm -rf "$stage"' EXIT HUP INT TERM
mkdir -p "$stage/scripts"
cp "$jar" "$stage/server.jar"
cp scripts/benchmark-release-gate.py "$stage/scripts/benchmark-release-gate.py"

docker volume create "$volume" >/dev/null
docker volume create "$maven_volume" >/dev/null
docker build -q -f scripts/Dockerfile.benchmark -t "$image" scripts >/dev/null
docker run --rm --network none --user root \
  --mount "type=volume,src=$volume,dst=/cache" \
  --mount "type=volume,src=$maven_volume,dst=/m2" \
  --entrypoint sh "$image" -c \
  'mkdir -p /cache/home /cache/gradle /cache/coursier /cache/sbt /cache/ivy2 && chown buildtools:buildtools /cache /cache/home /cache/gradle /cache/coursier /cache/sbt /cache/ivy2 /m2' >/dev/null

docker run --rm --network "$network" \
  --memory 4g --cpus 3 --pids-limit 512 \
  --cap-drop ALL --security-opt no-new-privileges --read-only \
  --tmpfs /tmp:rw,exec,size=1g,uid=100,gid=101 \
  --mount "type=bind,src=$stage,dst=/bench,readonly" \
  --mount "$maven_mount" \
  --mount "type=volume,src=$volume,dst=/cache" \
  --env HOME=/cache/home \
  --env COURSIER_CACHE=/cache/coursier \
  --env GRADLE_USER_HOME=/cache/gradle \
  --env SBT_OPTS='-Dsbt.boot.directory=/cache/sbt/boot -Dsbt.global.base=/cache/sbt/global -Dsbt.ivy.home=/cache/ivy2' \
  --env MAVEN_OPTS=-Dmaven.repo.local=/m2 \
  "$image" --jar /bench/server.jar --network-mode "$network" \
  --cache-state "${BENCHMARK_CACHE_STATE:-unspecified}" "$@"
