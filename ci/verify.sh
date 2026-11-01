#!/usr/bin/env bash
set -euo pipefail

controller_source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
protocol_source_dir="${DSP408_PROTOCOL_DIR:-${controller_source_dir}/../dsp408-protocol}"
protocol_ref="$(cat "${controller_source_dir}/ci/protocol-revision")"

if [[ ! "$protocol_ref" =~ ^[0-9a-f]{40}$ ]]; then
    printf '%s\n' 'Invalid protocol revision: expected a full 40-character lowercase commit SHA.' >&2
    exit 2
fi
if [[ ! -f "${protocol_source_dir}/pom.xml" ]]; then
    printf '%s\n' 'Missing dsp408-protocol source checkout.' 'Place it alongside the Controller or set DSP408_PROTOCOL_DIR to its directory.' >&2
    exit 2
fi
protocol_source_dir="$(cd -- "$protocol_source_dir" && pwd -P)"

# Never borrow the identity of an enclosing repository for a source archive.
source_identity() {
    local source_dir="$1" git_root revision status dirty
    if ! git_root="$(git -C "$source_dir" rev-parse --show-toplevel 2>/dev/null)" || [[ "$git_root" != "$source_dir" ]]; then
        printf '%s\n' unknown unknown
        return
    fi
    if ! revision="$(git -C "$source_dir" rev-parse --verify HEAD 2>/dev/null)" || [[ ! "$revision" =~ ^[0-9a-f]{40}$ ]]; then
        printf '%s\n' unknown unknown
        return
    fi
    dirty=unknown
    if status="$(git --no-optional-locks -C "$source_dir" status --porcelain=v1 --untracked-files=normal --ignore-submodules=none)"; then
        dirty=false
        if [[ -n "$status" ]]; then
            dirty=true
        fi
    fi
    printf '%s\n' "$revision" "$dirty"
}

mapfile -t controller_identity < <(source_identity "$controller_source_dir")
mapfile -t protocol_identity < <(source_identity "$protocol_source_dir")
if [[ "${protocol_identity[0]}" == unknown ]]; then
    printf '%s\n' 'Protocol source must be a Git checkout to verify the pinned revision.' >&2
    exit 2
fi
if [[ "${protocol_identity[0]}" != "$protocol_ref" ]]; then
    printf 'Protocol revision %s does not match ci/protocol-revision (%s).\n' "${protocol_identity[0]}" "$protocol_ref" >&2
    exit 2
fi

printf 'Controller source: %s dirty=%s\n' "${controller_identity[0]}" "${controller_identity[1]}"
printf 'Protocol source: %s dirty=%s\n' "${protocol_identity[0]}" "${protocol_identity[1]}"

mvn -B -ntp -f "${protocol_source_dir}/pom.xml" clean install
mvn -B -ntp -f "${controller_source_dir}/dsp408-parent/pom.xml" \
    "-Ddsp408.source.revision=${controller_identity[0]}" \
    "-Ddsp408.source.dirty=${controller_identity[1]}" \
    "-Ddsp408.protocol.revision=${protocol_identity[0]}" \
    "-Ddsp408.protocol.dirty=${protocol_identity[1]}" \
    clean install
