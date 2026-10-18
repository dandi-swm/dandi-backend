#!/bin/bash
# jansori 화면을 로컬 포트로 연다: ./jansori-tunnel.sh prod|loadtest
#   prod     → http://localhost:18082
#   loadtest → http://localhost:18083
# jansori task와 ECS 호스트를 매번 조회한다(task가 다시 뜨면 IP가 바뀐다).
# jansori SG는 8080을 ECS 호스트 SG에서만 열어 두어, 호스트를 거쳐 SSM 포트포워딩한다.
set -euo pipefail

: "${AWS_PROFILE:=dandi-nijika}"
: "${AWS_REGION:=ap-northeast-2}"
export AWS_PROFILE AWS_REGION AWS_PAGER=""

case "${1:-}" in
  prod) LOCAL_PORT=18082 ;;
  loadtest) LOCAL_PORT=18083 ;;
  *) echo "usage: $0 prod|loadtest" >&2; exit 1 ;;
esac

CLUSTER="nyummy-$1-cluster"
TASK_ARN="$(aws ecs list-tasks --cluster "$CLUSTER" --service-name "nyummy-$1-jansori-service" --query 'taskArns[0]' --output text)"
TASK_IP="$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK_ARN" \
  --query 'tasks[0].attachments[0].details[?name==`privateIPv4Address`].value' --output text)"
HOST_ID="$(aws ecs list-container-instances --cluster "$CLUSTER" --query 'containerInstanceArns[0]' --output text \
  | xargs -I{} aws ecs describe-container-instances --cluster "$CLUSTER" --container-instances {} \
      --query 'containerInstances[0].ec2InstanceId' --output text)"

echo "$1 jansori $TASK_IP:8080 → http://localhost:$LOCAL_PORT (via $HOST_ID)"
aws ssm start-session --target "$HOST_ID" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "host=$TASK_IP,portNumber=8080,localPortNumber=$LOCAL_PORT"
