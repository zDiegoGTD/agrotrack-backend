#!/bin/bash
# User data para las tres EC2 (Amazon Linux 2023): Docker + Compose + carpeta
# de despliegue. Se pega en "Advanced details -> User data" al crear la
# instancia. Idempotente: se puede volver a ejecutar a mano.
set -euxo pipefail

dnf update -y
dnf install -y docker git

# Docker Compose v2 como plugin
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

systemctl enable --now docker
usermod -aG docker ec2-user

# Kafka y Postgres agradecen esto; no molesta en las otras
sysctl -w vm.max_map_count=262144
echo 'vm.max_map_count=262144' >> /etc/sysctl.conf

# Donde subir.ps1 deja el codigo
mkdir -p /opt/agrotrack && chown ec2-user:ec2-user /opt/agrotrack

docker --version && docker compose version
