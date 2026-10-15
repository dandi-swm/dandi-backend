# syntax=docker/dockerfile:1
FROM grafana/alloy:v1.20.1

COPY config.alloy /etc/alloy/config.alloy

# host 네트워크 모드라 0.0.0.0에 바인드하면 호스트 포트를 점유한다.
# 루프백 + 다른 포트로 충돌을 피한다.
CMD ["run", \
     "/etc/alloy/config.alloy", \
     "--storage.path=/var/lib/alloy/data", \
     "--server.http.listen-addr=127.0.0.1:12346"]
