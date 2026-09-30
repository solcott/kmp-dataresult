#!/bin/bash
# PreToolUse(Read): keep generated output and near-default config out of the main context.
#
# detekt.yml is ~930 lines of mostly defaults. build/, .gradle/, .kotlin/ and kotlin-js-store/ are
# generated. The exception is the dependency-analysis report, which is how buildHealth findings get
# fixed by hand. Subagents pass, because what they read doesn't land in this context.
input=$(cat)

[ -n "$(jq -r '.agent_id // empty' <<<"$input")" ] && exit 0

path=$(jq -r '.tool_input.file_path // empty' <<<"$input")
root=${CLAUDE_PROJECT_DIR:-$(jq -r '.cwd' <<<"$input")}
rel=${path#"$root"/}
[ "$rel" = "$path" ] && exit 0 # outside the project

case "/$rel" in
  */build/reports/dependency-analysis/*) exit 0 ;;
  /detekt/detekt.yml | */build/* | */.gradle/* | */.kotlin/* | /kotlin-js-store/*)
    jq -n --arg rel "$rel" '{
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "deny",
        permissionDecisionReason: ("Not reading " + $rel + ": generated output or near-default config (see CLAUDE.md, Keeping context small). For build results, ask the dataresult-gradle-runner agent. For detekt, grep the one rule id you need.")
      }
    }'
    ;;
esac
exit 0
