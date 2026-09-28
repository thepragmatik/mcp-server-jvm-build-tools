#!/usr/bin/env sh
set -eu

# Build the image once; repeatable verifies reuse the host artifact cache read-only.
image="${DOCKER_VERIFY_IMAGE:-jvm-build-tools:2.0-local}"
repo="${MAVEN_REPOSITORY:-$HOME/.m2/repository}"

if [ ! -d "$repo" ]; then
  echo "Maven repository is missing; set MAVEN_REPOSITORY to an existing cache." >&2
  exit 2
fi

docker run --rm \
  --network none \
  --memory 4g --cpus 3 --pids-limit 512 \
  --cap-drop ALL --security-opt no-new-privileges \
  --read-only \
  --tmpfs /tmp:rw,exec,size=1g,uid=100,gid=101 \
  --tmpfs /work:rw,exec,size=2g,uid=100,gid=101 \
  --tmpfs /home/buildtools:rw,exec,size=512m,uid=100,gid=101 \
  --mount "type=bind,src=$(pwd),dst=/source,readonly" \
  --mount "type=bind,src=$repo,dst=/m2,readonly" \
  --entrypoint sh "$image" -c '
    cd /source
    tar --exclude=.git --exclude=target --exclude=.agents --exclude=.codex \
      --exclude=.env --exclude=.env.* --exclude="*.pem" --exclude="*.key" \
      -cf - . | tar -C /work -xf -
    cd /work
    mvn -o -B verify -Dmaven.repo.local=/m2 --no-transfer-progress
  '
