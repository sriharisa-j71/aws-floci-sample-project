#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ENDPOINT="${AWS_ENDPOINT_URL:-http://localhost:4566}"
DEFAULT_GROUP="/aws/lambda/employee-sal-processor"

usage() {
  cat <<EOF
Usage: $(basename "$0") <command> [options]

Commands:
  groups                              List all log groups
  search <pattern> [group]            Search logs by pattern in group (default: $DEFAULT_GROUP)
  errors   [group]                    Show ERROR/WARN level logs
  recent   [group]                    Show recent log events (last 10)
  publish                             Show all SQS publish events from file-handler
  employees                           Show all employee API check results from sal-processor
  tail     [group]                    Stream new log events (poll every 3s)

Options:
  --group <name>   Log group name (default per command)
  --pattern <str>  Filter pattern
  --endpoint <url> Floci endpoint (default: $ENDPOINT)
  --help           Show this message

Examples:
  $(basename "$0") groups
  $(basename "$0") search "exists=true"
  $(basename "$0") search "ERROR" /aws/lambda/employee-file-handler
  $(basename "$0") errors
  $(basename "$0") employees
EOF
  exit 1
}

CMD="${1:-}"
[[ -z "$CMD" ]] && usage
shift

GROUP=""
PATTERN=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --group)    GROUP="$2";    shift 2 ;;
    --pattern)  PATTERN="$2";  shift 2 ;;
    --endpoint) ENDPOINT="$2"; shift 2 ;;
    --help)     usage                  ;;
    *)          break                  ;;
  esac
done

# Remaining positional args
POS_ARGS=("$@")

aws_logs() {
  aws --endpoint-url "$ENDPOINT" logs "$@"
}

case "$CMD" in
  groups)
    echo "=== Log Groups ==="
    aws_logs describe-log-groups --query 'logGroups[].logGroupName' --output table
    ;;

  search)
    PATTERN="${PATTERN:-${POS_ARGS[0]:-}}"
    GROUP="${GROUP:-${POS_ARGS[1]:-$DEFAULT_GROUP}}"
    [[ -z "$PATTERN" ]] && { echo "ERROR: --pattern required"; usage; }
    echo "=== Searching '$PATTERN' in $GROUP ==="
    aws_logs filter-log-events \
      --log-group-name "$GROUP" \
      --filter-pattern "$PATTERN" \
      --query 'events[*].message' \
      --output json 2>/dev/null | python3 -c "
import sys, json
data = json.load(sys.stdin)
if not data:
    print('No matches')
    sys.exit(0)
for line in data:
    try:
        m = json.loads(line)
        print(f\"  [{m.get('level','?')}] {m.get('message','')}\")
    except json.JSONDecodeError:
        print(f'  {line}')
print(f'  --- {len(data)} matches ---')
"
    ;;

  errors)
    GROUP="${GROUP:-${POS_ARGS[0]:-$DEFAULT_GROUP}}"
    echo "=== Errors/Warnings in $GROUP ==="
    aws_logs filter-log-events \
      --log-group-name "$GROUP" \
      --filter-pattern "?" \
      --query 'events[*].message' \
      --output json 2>/dev/null | python3 -c "
import sys, json
data = json.load(sys.stdin)
found = []
for line in data:
    try:
        m = json.loads(line)
        lvl = m.get('level','')
        if lvl in ('ERROR','WARN'):
            found.append(f\"  [{lvl}] {m.get('message','')}\")
    except json.JSONDecodeError:
        pass
if not found:
    print('No errors/warnings')
else:
    for f in found:
        print(f)
    print(f'  --- {len(found)} entries ---')
"
    ;;

  recent)
    GROUP="${GROUP:-${POS_ARGS[0]:-$DEFAULT_GROUP}}"
    LIMIT="${POS_ARGS[1]:-10}"
    echo "=== Recent $LIMIT events in $GROUP ==="
    STREAM=$(aws_logs describe-log-streams \
      --log-group-name "$GROUP" \
      --query 'logStreams[-1].logStreamName' --output text 2>/dev/null)
    [[ -z "$STREAM" || "$STREAM" == "None" ]] && { echo "No streams found"; exit 0; }
    aws_logs get-log-events \
      --log-group-name "$GROUP" \
      --log-stream-name "$STREAM" \
      --limit "$LIMIT" \
      --query 'events[*].message' \
      --output json 2>/dev/null | python3 -c "
import sys, json
data = json.load(sys.stdin)
if not data:
    print('No events')
    sys.exit(0)
for line in data:
    try:
        m = json.loads(line)
        print(f\"  [{m.get('level','?')}] {m.get('message','')}\")
    except json.JSONDecodeError:
        print(f'  {line}')
"
    ;;

  publish)
    GROUP="/aws/lambda/employee-file-handler"
    echo "=== SQS Publish Events ==="
    aws_logs filter-log-events \
      --log-group-name "$GROUP" \
      --filter-pattern "Published" \
      --query 'events[*].message' \
      --output json 2>/dev/null | python3 -c "
import sys, json
data = json.load(sys.stdin)
if not data:
    print('No publish events')
    sys.exit(0)
for line in data:
    m = json.loads(line)
    print(f\"  {m.get('message','')}\")
print(f'  --- {len(data)} events across all runs ---')
"
    ;;

  employees)
    GROUP="/aws/lambda/employee-sal-processor"
    echo "=== Employee API Check Results ==="
    aws_logs filter-log-events \
      --log-group-name "$GROUP" \
      --filter-pattern "exists=" \
      --query 'events[*].message' \
      --output json 2>/dev/null | python3 -c "
import sys, json
data = json.load(sys.stdin)
seen = set()
for line in data:
    m = json.loads(line)
    msg = m.get('message','')
    if 'exists=' in msg and msg not in seen:
        seen.add(msg)
        print(f'  {msg}')
print(f'  --- {len(seen)} unique results ---')
"
    ;;

  tail)
    GROUP="${GROUP:-${POS_ARGS[0]:-$DEFAULT_GROUP}}"
    echo "=== Tailing $GROUP (Ctrl+C to stop) ==="
    TOKEN=""
    while true; do
      STREAM=$(aws_logs describe-log-streams \
        --log-group-name "$GROUP" \
        --query 'logStreams[-1].logStreamName' --output text 2>/dev/null)
      if [[ -n "$STREAM" && "$STREAM" != "None" ]]; then
        ARGS=()
        [[ -n "$TOKEN" ]] && ARGS+=(--next-token "$TOKEN")
        RESULT=$(aws_logs get-log-events \
          --log-group-name "$GROUP" \
          --log-stream-name "$STREAM" \
          "${ARGS[@]}" \
          --query '[events[*].message, nextForwardToken]' \
          --output json 2>/dev/null) || true
        if [[ -n "$RESULT" ]]; then
          EVENTS=$(echo "$RESULT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
for line in d[0]:
    try:
        m = json.loads(line)
        print(f\"[{m.get('level','?')}] {m.get('message','')}\")
    except:
        print(line)
print('__TOKEN__:' + d[1])
" 2>/dev/null) || true
          if [[ -n "$EVENTS" ]]; then
            TOKEN=$(echo "$EVENTS" | grep '__TOKEN__:' | sed 's/__TOKEN__://')
            echo "$EVENTS" | grep -v '__TOKEN__:'
          fi
        fi
      fi
      sleep 3
    done
    ;;

  *)
    usage
    ;;
esac
