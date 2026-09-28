#!/usr/bin/env sh
set -eu

# Build the image once; repeatable verifies reuse the host artifact cache read-only.
# Stage only the committed source snapshot, never ignored worktrees or local files.
image="${DOCKER_VERIFY_IMAGE:-jvm-build-tools:2.0-local}"
repo="${MAVEN_REPOSITORY:-$HOME/.m2/repository}"
source_root=$(CDPATH= cd "$(dirname "$0")/.." && pwd)

if [ ! -d "$repo" ]; then
  echo "Maven repository is missing; set MAVEN_REPOSITORY to an existing cache." >&2
  exit 2
fi
repo=$(CDPATH= cd "$repo" && pwd -P)

# POSIX sh reports only the final command's status in a pipeline. Stage the
# small archive first so a failed git archive cannot be hidden by Docker.
snapshot=$(mktemp "${TMPDIR:-/tmp}/mcp-docker-verify.XXXXXX")
trap 'rm -f "$snapshot"' 0
git -C "$source_root" archive --format=tar HEAD > "$snapshot"

docker run --rm -i \
  --network none \
  --memory 4g --cpus 3 --pids-limit 512 \
  --cap-drop ALL --security-opt no-new-privileges \
  --read-only \
  --tmpfs /tmp:rw,exec,size=1g,uid=100,gid=101 \
  --tmpfs /work:rw,exec,size=2g,uid=100,gid=101 \
  --tmpfs /home/buildtools:rw,exec,size=512m,uid=100,gid=101 \
  --mount "type=bind,src=$repo,dst=/m2,readonly" \
  --entrypoint sh "$image" -c '
    set -eu
    tar -C /work -xf -
    cd /work
    mvn -o -B verify -Dmaven.repo.local=/m2 --no-transfer-progress
  ' < "$snapshot"
