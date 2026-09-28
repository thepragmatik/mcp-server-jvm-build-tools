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

repo="${MAVEN_REPOSITORY:-$HOME/.m2/repository}"
jar="target/mcp-server-jvm-build-tools.jar"
image="${BENCHMARK_IMAGE:-jvm-build-tools:benchmark}"
volume="${BENCHMARK_CACHE_VOLUME:-jvm-benchmark-public-artifacts}"
if [ ! -f "$jar" ] || [ ! -d "$repo" ]; then
  echo 'Build the packaged jar and provide a Maven artifact repository first.' >&2
  exit 2
fi

stage="$(mktemp -d "${TMPDIR:-/tmp}/jvm-benchmark.XXXXXXXX")"
trap 'rm -rf "$stage"' EXIT HUP INT TERM
mkdir -p "$stage/scripts"
cp "$jar" "$stage/server.jar"
cp scripts/benchmark-release-gate.py "$stage/scripts/benchmark-release-gate.py"

docker volume create "$volume" >/dev/null
docker build -q -f scripts/Dockerfile.benchmark -t "$image" scripts >/dev/null
docker run --rm --network none --user root \
  --mount "type=volume,src=$volume,dst=/cache" \
  --entrypoint sh "$image" -c \
  'mkdir -p /cache/home /cache/gradle /cache/coursier /cache/sbt /cache/ivy2 && chown -R buildtools:buildtools /cache' >/dev/null

docker run --rm --network "$network" \
  --memory 4g --cpus 3 --pids-limit 512 \
  --cap-drop ALL --security-opt no-new-privileges --read-only \
  --tmpfs /tmp:rw,exec,size=1g,uid=100,gid=101 \
  --mount "type=bind,src=$stage,dst=/bench,readonly" \
  --mount "type=bind,src=$repo,dst=/m2,readonly" \
  --mount "type=volume,src=$volume,dst=/cache" \
  --env HOME=/cache/home \
  --env COURSIER_CACHE=/cache/coursier \
  --env GRADLE_USER_HOME=/cache/gradle \
  --env SBT_OPTS='-Dsbt.boot.directory=/cache/sbt/boot -Dsbt.global.base=/cache/sbt/global -Dsbt.ivy.home=/cache/ivy2' \
  --env MAVEN_OPTS=-Dmaven.repo.local=/m2 \
  "$image" --jar /bench/server.jar --network-mode "$network" \
  --cache-state "${BENCHMARK_CACHE_STATE:-unspecified}" "$@"
