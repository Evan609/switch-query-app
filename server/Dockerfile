FROM rockylinux:9
# 可选部署形态：容器化。宿主机只需 podman/docker，Python 版本由镜像固定，
# 因此 Rocky 8 与 Rocky 9 上的运行行为完全一致。

RUN dnf install -y python3.11 && dnf clean all && rm -rf /var/cache/dnf

WORKDIR /opt/switch-auth
COPY app.py /opt/switch-auth/app.py

EXPOSE 8443
ENTRYPOINT ["/usr/bin/python3.11", "/opt/switch-auth/app.py", "--config", "/opt/switch-auth/config.json"]

# 使用方式（宿主机上）：
#   podman build -t switch-auth:1.0 .
#   podman run -d --name switch-auth --restart=always -p 8443:8443 \
#     -v /opt/switch-auth:/opt/switch-auth:Z switch-auth:1.0
# 注意：容器方式下 data/ config.json cert/ 均在宿主机 /opt/switch-auth 内，迁移与备份方式不变。
