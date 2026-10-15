# syntax=docker/dockerfile:1
FROM grafana/alloy:v1.20.1

# Alloy 기본 설정 경로. 설정이 이미지에 들어가므로
# "이미지 태그 = 설정 버전"이 되어 롤백이 단순해진다.
COPY config.alloy /etc/alloy/config.alloy

# 기본 CMD는 run + config + storage.path 세 개다.
# 여기에 listen-addr만 더해 디버깅 여지를 남긴다.
CMD ["run", \
     "/etc/alloy/config.alloy", \
     "--storage.path=/var/lib/alloy/data", \
     "--server.http.listen-addr=0.0.0.0:12345"]