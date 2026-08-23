#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

readonly APP_DIR="/opt/order-platform"
readonly ENV_FILE="${APP_DIR}/.env"
readonly COMPOSE_FILE="${APP_DIR}/compose.prod.yml"
readonly RESOURCES_FILE="${APP_DIR}/compose.resources.yml"
readonly RELEASE_DIR="${APP_DIR}/releases"
readonly LOCK_FILE="${APP_DIR}/.deploy.lock"
readonly APP_SERVICE="app"
readonly HEALTH_URL="https://order-api.qnnm-js.site/health"

readonly MODE="${1:-}"
readonly NEW_TAG="${2:-}"
readonly EXPECTED_DIGEST="${3:-}"

ENV_CHANGED=0
ENV_BACKUP=""

log() {
    printf '[%s] %s\n' \
        "$(date '+%Y-%m-%d %H:%M:%S %z')" \
        "$*"
}

die() {
    printf '[ERROR] %s\n' "$*" >&2
    exit 1
}

usage() {
    printf '%s\n' \
        "Usage:" \
        "  $0 check  sha-<40-char-git-sha> sha256:<64-char-digest>" \
        "  $0 deploy sha-<40-char-git-sha> sha256:<64-char-digest>"
}

compose() {
    docker compose \
        --env-file "$ENV_FILE" \
        -f "$COMPOSE_FILE" \
        -f "$RESOURCES_FILE" \
        "$@"
}

read_env_value() {
    local key="$1"

    sed -n "s/^${key}=//p" "$ENV_FILE" |
        head -n 1 |
        sed 's/\r$//'
}

image_repo_digest() {
    local image_reference="$1"

    docker image inspect "$image_reference" \
        --format '{{range .RepoDigests}}{{println .}}{{end}}' |
        awk -F '@' -v image_name="$IMAGE_NAME" \
            '$1 == image_name { print $2; exit }'
}

rollback_on_failure() {
    local exit_code="$1"

    trap - EXIT

    if [[ "$exit_code" -ne 0 && "$ENV_CHANGED" -eq 1 ]]; then
        set +e

        log "Deployment failed; starting automatic rollback"
        log "Restoring environment backup: ${ENV_BACKUP}"

        cp -a "$ENV_BACKUP" "$ENV_FILE"
        chmod 600 "$ENV_FILE"

        if compose config -q &&
            compose up \
                -d \
                --no-deps \
                --force-recreate \
                --pull never \
                --wait \
                --wait-timeout 180 \
                "$APP_SERVICE" &&
            curl \
                --fail \
                --silent \
                --show-error \
                --retry 5 \
                --retry-delay 2 \
                "$HEALTH_URL" \
                >/dev/null
        then
            log "Automatic rollback succeeded"
        else
            log "Automatic rollback FAILED; manual intervention is required"
        fi
    fi

    exit "$exit_code"
}

trap 'rollback_on_failure $?' EXIT

if [[ "$MODE" != "check" && "$MODE" != "deploy" ]]; then
    usage
    exit 64
fi

if [[ ! "$NEW_TAG" =~ ^sha-[0-9a-f]{40}$ ]]; then
    die "Invalid image tag: ${NEW_TAG}"
fi

if [[ ! "$EXPECTED_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    die "Invalid image digest: ${EXPECTED_DIGEST}"
fi

if [[ "$EUID" -ne 0 ]]; then
    die "This script must run as root or through sudo"
fi

for required_command in \
    docker \
    curl \
    sed \
    awk \
    flock \
    stat
do
    command -v "$required_command" >/dev/null ||
        die "Required command not found: ${required_command}"
done

for required_file in \
    "$ENV_FILE" \
    "$COMPOSE_FILE" \
    "$RESOURCES_FILE"
do
    [[ -f "$required_file" ]] ||
        die "Required file not found: ${required_file}"
done

if [[ "$(stat -c '%a' "$ENV_FILE")" != "600" ]]; then
    die "${ENV_FILE} must have permission mode 600"
fi

cd "$APP_DIR"

exec 9>"$LOCK_FILE"

if ! flock -n 9; then
    die "Another OrderPlatform deployment is already running"
fi

# 防止调用脚本的 Shell 中存在同名 export 变量，
# 覆盖服务器 .env 中的生产配置。
for env_key in \
    IMAGE_NAME \
    IMAGE_TAG \
    APP_HOST_PORT \
    MYSQL_DATABASE \
    MYSQL_USER \
    MYSQL_PASSWORD \
    MYSQL_ROOT_PASSWORD \
    REDIS_PASSWORD \
    RABBITMQ_USER \
    RABBITMQ_PASSWORD \
    STOCK_STRATEGY \
    TZ \
    JAVA_TOOL_OPTIONS
do
    unset "$env_key" || true
done

if [[ "$(grep -c '^IMAGE_NAME=' "$ENV_FILE")" -ne 1 ]]; then
    die "${ENV_FILE} must contain exactly one IMAGE_NAME entry"
fi

if [[ "$(grep -c '^IMAGE_TAG=' "$ENV_FILE")" -ne 1 ]]; then
    die "${ENV_FILE} must contain exactly one IMAGE_TAG entry"
fi

IMAGE_NAME="$(read_env_value IMAGE_NAME)"
CURRENT_TAG="$(read_env_value IMAGE_TAG)"

readonly IMAGE_NAME
readonly CURRENT_TAG

if [[ ! "$IMAGE_NAME" =~ ^ghcr\.io/[a-z0-9._-]+/[a-z0-9._-]+$ ]]; then
    die "Invalid IMAGE_NAME in ${ENV_FILE}: ${IMAGE_NAME}"
fi

if [[ ! "$CURRENT_TAG" =~ ^sha-[0-9a-f]{40}$ ]]; then
    die "Invalid current IMAGE_TAG in ${ENV_FILE}: ${CURRENT_TAG}"
fi

readonly NEW_IMAGE="${IMAGE_NAME}:${NEW_TAG}"
readonly EXPECTED_CURRENT_IMAGE="${IMAGE_NAME}:${CURRENT_TAG}"
readonly EXPECTED_REVISION="${NEW_TAG#sha-}"

CURRENT_SHORT="${CURRENT_TAG#sha-}"
CURRENT_SHORT="${CURRENT_SHORT:0:7}"

NEW_SHORT="${NEW_TAG#sha-}"
NEW_SHORT="${NEW_SHORT:0:7}"

readonly CURRENT_SHORT
readonly NEW_SHORT

log "Validating merged Docker Compose configuration"
compose config -q

CURRENT_CONTAINER="$(compose ps -q "$APP_SERVICE")"
readonly CURRENT_CONTAINER

if [[ -z "$CURRENT_CONTAINER" ]]; then
    die "Current application container was not found"
fi

CURRENT_RUNNING_IMAGE="$(
    docker inspect "$CURRENT_CONTAINER" \
        --format '{{.Config.Image}}'
)"

CURRENT_IMAGE_ID="$(
    docker inspect "$CURRENT_CONTAINER" \
        --format '{{.Image}}'
)"

CURRENT_HEALTH="$(
    docker inspect "$CURRENT_CONTAINER" \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'
)"

readonly CURRENT_RUNNING_IMAGE
readonly CURRENT_IMAGE_ID
readonly CURRENT_HEALTH

if [[ "$CURRENT_RUNNING_IMAGE" != "$EXPECTED_CURRENT_IMAGE" ]]; then
    die "Running image does not match .env: running=${CURRENT_RUNNING_IMAGE}, expected=${EXPECTED_CURRENT_IMAGE}"
fi

if [[ "$CURRENT_HEALTH" != "healthy" ]]; then
    die "Current application is not healthy: ${CURRENT_HEALTH}"
fi

log "Pulling candidate image: ${NEW_IMAGE}"
docker pull "$NEW_IMAGE"

ACTUAL_DIGEST="$(image_repo_digest "$NEW_IMAGE")"

NEW_IMAGE_ID="$(
    docker image inspect "$NEW_IMAGE" \
        --format '{{.Id}}'
)"

NEW_REVISION="$(
    docker image inspect "$NEW_IMAGE" \
        --format '{{index .Config.Labels "org.opencontainers.image.revision"}}'
)"

NEW_RUNTIME_USER="$(
    docker image inspect "$NEW_IMAGE" \
        --format '{{.Config.User}}'
)"

readonly ACTUAL_DIGEST
readonly NEW_IMAGE_ID
readonly NEW_REVISION
readonly NEW_RUNTIME_USER

if [[ -z "$ACTUAL_DIGEST" ]]; then
    die "Candidate image has no matching GHCR repository digest"
fi

if [[ "$ACTUAL_DIGEST" != "$EXPECTED_DIGEST" ]]; then
    die "Digest mismatch: expected=${EXPECTED_DIGEST}, actual=${ACTUAL_DIGEST}"
fi

if [[ "$NEW_REVISION" != "$EXPECTED_REVISION" ]]; then
    die "Revision mismatch: expected=${EXPECTED_REVISION}, actual=${NEW_REVISION}"
fi

if [[ "$NEW_RUNTIME_USER" != "orderplatform:orderplatform" ]]; then
    die "Unexpected runtime user: ${NEW_RUNTIME_USER}"
fi

mkdir -p "$RELEASE_DIR"
chmod 755 "$RELEASE_DIR"

RELEASE_FILE="${RELEASE_DIR}/${NEW_SHORT}.release"
readonly RELEASE_FILE

if [[ -f "$RELEASE_FILE" ]]; then
    RECORDED_DIGEST="$(
        sed -n 's/^IMAGE_DIGEST=//p' "$RELEASE_FILE" |
            head -n 1
    )"

    if [[ "$RECORDED_DIGEST" != "$ACTUAL_DIGEST" ]]; then
        die "Existing release record conflicts with candidate digest"
    fi
fi

printf '%s\n' \
    "mode=${MODE}" \
    "current_image=${CURRENT_RUNNING_IMAGE}" \
    "current_image_id=${CURRENT_IMAGE_ID}" \
    "candidate_image=${NEW_IMAGE}" \
    "candidate_image_id=${NEW_IMAGE_ID}" \
    "candidate_digest=${ACTUAL_DIGEST}" \
    "candidate_revision=${NEW_REVISION}" \
    "candidate_user=${NEW_RUNTIME_USER}"

if [[ "$MODE" == "check" ]]; then
    log "Candidate image preflight check succeeded"
    exit 0
fi

if [[ "$CURRENT_TAG" == "$NEW_TAG" &&
      "$CURRENT_IMAGE_ID" == "$NEW_IMAGE_ID" ]]; then
    curl \
        --fail \
        --silent \
        --show-error \
        "$HEALTH_URL" \
        >/dev/null

    log "Requested release is already deployed and healthy"
    exit 0
fi

DEPLOYMENT_TIMESTAMP="$(date -u '+%Y%m%dT%H%M%SZ')"

ENV_BACKUP="${APP_DIR}/.env.backup-before-${NEW_SHORT}-from-${CURRENT_SHORT}-${DEPLOYMENT_TIMESTAMP}"

readonly DEPLOYMENT_TIMESTAMP
readonly ENV_BACKUP

log "Backing up current production environment"
cp -a "$ENV_FILE" "$ENV_BACKUP"
chmod 600 "$ENV_BACKUP"

ENV_CHANGED=1

log "Updating IMAGE_TAG: ${CURRENT_TAG} -> ${NEW_TAG}"

sed -i \
    "s/^IMAGE_TAG=.*/IMAGE_TAG=${NEW_TAG}/" \
    "$ENV_FILE"

chmod 600 "$ENV_FILE"

if [[ "$(read_env_value IMAGE_TAG)" != "$NEW_TAG" ]]; then
    die "Failed to update IMAGE_TAG in ${ENV_FILE}"
fi

log "Validating updated Docker Compose configuration"
compose config -q

log "Recreating application container only"

compose up \
    -d \
    --no-deps \
    --force-recreate \
    --pull never \
    --wait \
    --wait-timeout 180 \
    "$APP_SERVICE"

NEW_CONTAINER="$(compose ps -q "$APP_SERVICE")"

RUNNING_IMAGE="$(
    docker inspect "$NEW_CONTAINER" \
        --format '{{.Config.Image}}'
)"

RUNNING_IMAGE_ID="$(
    docker inspect "$NEW_CONTAINER" \
        --format '{{.Image}}'
)"

RUNNING_HEALTH="$(
    docker inspect "$NEW_CONTAINER" \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'
)"

RUNNING_RESTART_COUNT="$(
    docker inspect "$NEW_CONTAINER" \
        --format '{{.RestartCount}}'
)"

readonly NEW_CONTAINER
readonly RUNNING_IMAGE
readonly RUNNING_IMAGE_ID
readonly RUNNING_HEALTH
readonly RUNNING_RESTART_COUNT

if [[ "$RUNNING_IMAGE" != "$NEW_IMAGE" ]]; then
    die "Running container uses unexpected image: ${RUNNING_IMAGE}"
fi

if [[ "$RUNNING_IMAGE_ID" != "$NEW_IMAGE_ID" ]]; then
    die "Running image ID does not match candidate image ID"
fi

if [[ "$RUNNING_HEALTH" != "healthy" ]]; then
    die "New application container is not healthy: ${RUNNING_HEALTH}"
fi

log "Checking public HTTPS readiness endpoint"

curl \
    --fail \
    --silent \
    --show-error \
    --retry 5 \
    --retry-delay 2 \
    "$HEALTH_URL" \
    >/dev/null

if [[ ! -f "$RELEASE_FILE" ]]; then
    TEMP_RELEASE="$(
        mktemp "${RELEASE_DIR}/.${NEW_SHORT}.release.XXXXXX"
    )"

    {
        printf 'IMAGE_NAME=%s\n' "$IMAGE_NAME"
        printf 'IMAGE_TAG=%s\n' "$NEW_TAG"
        printf 'IMAGE_DIGEST=%s\n' "$ACTUAL_DIGEST"
        printf 'IMAGE_ID=%s\n' "$NEW_IMAGE_ID"
        printf 'GIT_REVISION=%s\n' "$NEW_REVISION"
        printf 'RUNTIME_USER=%s\n' "$NEW_RUNTIME_USER"
        printf 'PREVIOUS_IMAGE=%s\n' "$CURRENT_RUNNING_IMAGE"
        printf 'PREVIOUS_IMAGE_ID=%s\n' "$CURRENT_IMAGE_ID"
        printf 'ENV_BACKUP=%s\n' "$(basename "$ENV_BACKUP")"
        printf 'DEPLOYED_AT=%s\n' \
            "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    } >"$TEMP_RELEASE"

    chmod 644 "$TEMP_RELEASE"
    mv "$TEMP_RELEASE" "$RELEASE_FILE"
fi

ENV_CHANGED=0

log "Deployment succeeded"

printf '%s\n' \
    "release_file=${RELEASE_FILE}" \
    "running_image=${RUNNING_IMAGE}" \
    "running_image_id=${RUNNING_IMAGE_ID}" \
    "health=${RUNNING_HEALTH}" \
    "restart_count=${RUNNING_RESTART_COUNT}" \
    "rollback_env=${ENV_BACKUP}"