#!/bin/bash
# PreToolUse(Bash): send Gradle from the main session to the dataresult-gradle-runner agent.
#
# Every Gradle task here fans out across seven targets, and the log that comes back is thousands of
# lines of noise in the main context. The runner returns a verdict instead. Subagents (the runner
# itself, /precommit's fork) run Gradle on purpose, so a call that carries an agent_id passes.
#
# Matches `gradle`/`gradlew` only in command position, so `cat gradle.properties` or
# `git diff gradle/libs.versions.toml` pass.
input=$(cat)

[ -n "$(jq -r '.agent_id // empty' <<<"$input")" ] && exit 0

pattern='(^|[;&|(\n]|\$\()\s*([A-Za-z_][A-Za-z0-9_]*=\S*\s+)*(\S*/)?gradlew?(\s|$)'
if jq -e --arg re "$pattern" '.tool_input.command // "" | test($re)' <<<"$input" >/dev/null; then
  jq -n '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: "Run Gradle through the dataresult-gradle-runner agent (or /precommit before a commit), not inline Bash: the raw log across 7 targets floods this context. The runner returns a short verdict with test counts."
    }
  }'
fi
exit 0
