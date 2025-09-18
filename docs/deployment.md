# 贪吃蛇算法竞技平台部署指南

本指南将详细介绍如何部署贪吃蛇算法竞技平台后端系统。我们提供两种核心部署方案，以满足从快速本地体验到生产环境水平扩展的不同需求：

1.  **单机一体化部署**：在单台服务器上运行所有服务，包含一个 Controller 和一个 Worker 节点。此方案适合开发、测试或小规模竞赛场景。
2.  **多机分布式部署**：在单机部署的基础上，将 Worker 节点作为独立服务部署到其他服务器，以实现计算能力和任务处理能力的水平扩展。

## 部署前置要求

在开始之前，请确保您的所有部署节点（无论是单机还是多机）均满足以下条件：
  * **操作系统**：推荐使用较新的 Linux 发行版（如 Ubuntu 22.04 LTS 及以上）。Worker 节点所在的宿主机内核必须启用 cgroup v2，并支持 `memory.peak` 指标（推荐 Linux 内核 ≥ 5.15）。这是沙箱进行精确资源统计的基础。
  * **软件环境**：已安装最新稳定版的 Docker 和 Docker Compose。
  * **CPU 架构同构**：所有 Worker 节点必须使用相同的 CPU 架构（例如，全部为 x86-64）。因为代码在一个节点上编译的二进制产物，需要能在其他任何节点上正确执行。

### 备选方案：使用 KVM 虚拟化

如果您的宿主机因内核版本过低而无法启用 cgroup v2，或因其他限制无法直接满足部署要求，可以考虑使用 KVM 创建一个满足条件的虚拟机（VM）来部署 Worker 节点。

在网络配置方面，我们提供两种思路供您权衡：
  * **桥接模式 (Bridged Networking)**：此方案能够为虚拟机提供近乎原生的网络性能，使其如同物理网络中的独立主机一样获取 IP 地址。但配置过程较为复杂，涉及对宿主机网络堆栈的修改，错误配置有导致宿主机网络中断的风险。
  * **虚拟组网 (VPN)**：一个更稳妥且灵活的方案是，让虚拟机使用 KVM 默认的 NAT 网络模式，然后在主节点和 Worker 虚拟机内部署虚拟组网工具（如 Tailscale）。这类工具能构建一个安全的覆盖网络（Overlay Network），并会智能地尝试在节点间建立低延迟的**点对点直连**通信，在极大简化网络配置的同时保证了高效连接。

-----

## 方案一：单机一体化部署

此方案将平台的所有组件（包括 Controller、Worker、数据库、消息队列等）部署在同一台机器上，是快速启动和运行系统的最简便方式。

### 部署步骤

1.  **获取代码与配置**
    首先，克隆项目仓库，并进入部署目录 `docker-compose-deploy`。该目录包含了部署所需的全部配置文件。

    ```bash
    git clone https://github.com/SageSeekerSociety/snake.git
    cd snake/docker-compose-deploy
    ```

2.  **配置环境变量**
    复制环境变量示例文件 `sample.env` 为 `.env`。

    ```bash
    cp sample.env .env
    ```

    接下来，编辑 `.env` 文件。**在生产环境中，您必须修改以下关键安全配置：**

      * `JWT_SECRET`：用于签发认证 Token 的密钥，请务必修改为一个长且随机的字符串。
      * `POSTGRES_PASSWORD`, `MINIO_ROOT_PASSWORD`, `GRAFANA_ADMIN_PASSWORD` 等所有默认密码。
      * `FINAL_PORT`：Nginx 对外暴露的总端口，默认为 80。
      * 邮件服务 (`EMAIL_*`) 和 CORS (`CORS_ORIGINS`) 等相关配置，以匹配您的域名和前端地址。

3.  **启动服务**
    完成配置后，使用 Docker Compose 启动所有服务。

    ```bash
    docker compose up -d
    ```

    此命令会根据 `docker-compose.yml` 的定义，在后台拉取或构建镜像，并启动所有容器。您可以使用 `docker compose ps` 查看所有服务的运行状态。

4.  **验证部署**
    服务启动后，您可以通过浏览器访问 `http://<your-server-ip>:${FINAL_PORT}` 来查看前端页面。同时，可以访问 Grafana (`http://<your-server-ip>:3002`) 和 MinIO 控制台 (`http://<your-server-ip>:9001`) 来验证监控和存储服务是否正常工作。

-----

## 方案二：多机分布式部署（水平扩展 Worker）

当单机部署的计算资源无法满足大量并发的编译和执行请求时，您可以将无状态的 Worker 节点独立部署到其他机器上以实现水平扩展。

此方案分为两部分：**主节点**（运行 Controller 和其他基础服务）和**工作节点**（运行 `backend-worker`）。

### Part A: 配置主节点

主节点负责运行除 `backend-worker` 之外的所有核心服务。我们通过 `docker-compose.override.yml` 文件来禁用主节点上的 `backend-worker` 服务，实现职责分离。如果您想保留主节点上的 worker 服务，可以跳过这一步。

1.  **基础配置**
    遵循**方案一**的步骤 1 和 2，在主节点上准备好 `docker-compose-deploy` 目录和配置好的 `.env` 文件。

2.  **创建 Override 文件**
    在 `docker-compose-deploy` 目录下，创建一个名为 `docker-compose.override.yml` 的文件。这个文件将覆盖 `docker-compose.yml` 中的部分配置。填入以下内容以禁用 `backend-worker` 服务：

    ```yaml
    # docker-compose.override.yml
    services:
      backend-worker:
        profiles:
          - disabled
    ```

    这个配置利用 Docker Compose 的 `profiles` 特性，将 `backend-worker` 服务分配到一个名为 `disabled` 的配置文件中。默认情况下，Compose 不会启动任何带 `profile` 的服务，从而达到了禁用它的目的。

3.  **启动主节点服务**
    使用以下命令启动主节点。

    ```bash
    docker compose up -d
    ```

    此时，除了 `backend-worker` 之外的所有服务都会在主节点上运行。

### Part B: 配置并启动工作节点

现在，您可以在一台或多台新的服务器上部署 Worker 节点。

1.  **准备配置文件**
    在每台新的 Worker 节点服务器上，创建一个工作目录，并从项目仓库的 `docker-compose-deploy` 目录中复制以下文件到该工作目录：

      * `docker-compose.worker.yml`
      * `application-worker.yml`
      * `promtail-config.yml`

2.  **配置环境变量 (关键步骤)**
    在工作节点的工作目录下，创建一个 `.env` 文件。此文件的内容需要基于主节点的 `.env` 文件进行修改，**核心是将所有服务的主机名（hostname）替换为主节点的 IP 地址或域名**。

    这是一个 Worker 节点 `.env` 文件的配置示例（仅展示需修改部分）：

    ```dotenv
    # .env for worker node

    # --- 核心服务连接信息 (必须指向主节点的 IP 或域名) ---
    MAIN_NODE_IP=<your-main-node-ip>

    # RabbitMQ
    RABBITMQ_HOST=${MAIN_NODE_IP}
    # ... 其他 RabbitMQ 凭证从主节点 .env 复制 ...

    # MinIO
    MINIO_ENDPOINT=http://${MAIN_NODE_IP}:9000
    # ... 其他 MinIO 凭证从主节点 .env 复制 ...

    # Redis/Valkey
    REDIS_HOST=${MAIN_NODE_IP}

    # PostgreSQL (用于 cheese-auth)
    DB_HOST=${MAIN_NODE_IP}
    PRISMA_DATABASE_URL="postgresql://...url-format...@"${DB_HOST}":..."
    # ... 其他数据库凭证从主节点 .env 复制 ...

    # JWT 密钥 (必须与主节点 Controller 完全一致)
    JWT_SECRET="your-super-secret-jwt-key-that-is-at-least-256-bits-long"
    ```

    同时，您还需要修改 `promtail-config.yml`，将其中的 `clients.url` 指向主节点的 Loki 服务地址 (`http://<your-main-node-ip>:3100/loki/api/v1/push`)。

3.  **启动工作节点**
    确保 Worker 节点的宿主机满足前置要求后，在该节点的工作目录下执行以下命令：

    ```bash
    docker compose -f docker-compose.worker.yml up -d
    ```

    Worker 启动后会自动连接到主节点上的 RabbitMQ 并开始接收任务。您可以根据需要，在多台服务器上重复此步骤以部署更多的 Worker 节点。

-----

## 生产环境最佳实践

为了确保系统在生产环境中的安全、稳定和高效，我们提供以下建议：

  * **安全性**

      * **管理密钥**：强烈建议不要将 `JWT_SECRET`、数据库密码等敏感信息直接存储在 `.env` 文件中。应使用 Docker Secrets 等更安全的密钥管理工具。
      * **网络策略**：配置服务器防火墙，仅向公网暴露必要的端口（通常只有主节点的 `FINAL_PORT`）。节点间的服务通信（如 Worker 连接主节点的数据库、MQ）应尽可能在内网或 VPC 中进行。

  * **性能与扩展**

      * **监控与告警**：积极使用 Grafana 仪表盘监控 Worker 节点的 CPU、内存使用率以及任务队列的长度。设置告警规则，当负载达到阈值时，及时通知管理员增加 Worker 节点。
      * **Controller 扩展限制**：当前架构下，不建议直接水平扩展 Controller 节点，因为多实例可能导致结果消息处理和 SSE 推送的混乱。若未来有更高的并发需求，需要参考文档中提到的“黏性会话”或“中心化结果总线”方案对架构进行升级。

  * **数据持久化与备份**

      * Docker Compose 配置中使用了具名卷（Named Volumes）来持久化存储数据库、MinIO 等服务的数据。请务必定期对这些卷进行备份，以防数据丢失。您可以在宿主机的 `/var/lib/docker/volumes/` 目录下找到它们。

-----

## 附录：使用 KVM 部署 Worker 节点教程

本教程以 Ubuntu/Debian 系统为例，介绍如何使用 KVM 和推荐的 Tailscale 组网方案来创建一个独立的 Worker 节点环境。

### 第 1 步：在宿主机上安装 KVM 及相关工具

执行以下命令安装 KVM 及其管理工具：

```bash
sudo apt update
sudo apt install -y qemu-kvm libvirt-daemon-system libvirt-clients bridge-utils virt-install
```

### 第 2 步：创建虚拟机

使用 `virt-install` 命令创建一个新的虚拟机。我们推荐使用较新的 Linux 发行版（如 Ubuntu 22.04 LTS），以确保默认启用了 cgroup v2。

```bash
# 下载 Ubuntu 22.04 Cloud Image
wget https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-amd64.img

# (可选) 调整镜像大小
qemu-img resize jammy-server-cloudimg-amd64.img +20G

# 创建虚拟机 (使用默认 NAT 网络)
virt-install --name snake-worker-vm \
--ram 4096 \
--vcpus 2 \
--disk path=jammy-server-cloudimg-amd64.img,format=qcow2 \
--os-variant ubuntu22.04 \
--virt-type kvm \
--graphics none \
--network network=default \
--import
```

**注意**：Cloud Image 默认没有设置密码，您可能需要使用 `virt-customize` 或在首次启动后通过 `virsh console snake-worker-vm` 登录并设置用户和密码。

### 第 3 步：配置虚拟机网络

虚拟机的网络配置是确保 Worker 节点能与主节点正常通信的关键。在此我们详细介绍两种主流方案。

#### 方案 A：桥接模式 (高级)

此方案将虚拟机的网络接口桥接到宿主机的物理网卡上，使虚拟机成为物理网络的直接成员。

  * **优势**：网络结构简单明了，虚拟机直接从您的局域网路由器获取 IP，无需任何 NAT 或端口转发，可获得最佳的网络性能。
  * **挑战**：配置过程侵入性较强，需要修改宿主机的网络配置文件（例如 Ubuntu 的 Netplan 或 CentOS 的 ifcfg），操作不当极易导致宿主机断网。
  * **建议**：由于具体配置步骤与您的操作系统版本及网络环境高度相关，我们不在此提供统一的配置指令。若您希望采用此方案，请自行搜索与您的环境匹配的教程，例如“Ubuntu 22.04 KVM configure network bridge”。

#### 方案 B：NAT 模式与 Tailscale (推荐)

此方案保留 KVM 默认的、隔离的 NAT 网络，并通过 Tailscale 在其上层构建一个安全的虚拟局域网，是兼顾了简易性、安全性与性能的理想选择。

  * **优势**：无需对宿主机网络进行任何修改，配置简单且风险极低。Tailscale 会利用 STUN 等技术进行 NAT 穿透，尽可能在您的主节点和 Worker 虚拟机之间建立**点对点 (P2P) 的加密直连**。这确保了绝大多数情况下的通信延迟都非常低。只有在直连无法建立的极端情况下，流量才会通过其加密的 DERP 中继服务器转发。

鉴于以上优势，后续步骤将以方案 B 为例进行指导。

> 截止该文档完成时，Tailscale 相关的服务在国内能够直接连接。如果未来出现不可抗力导致相关服务被阻止，请寻找替代方案，或自行配置好相关网络环境。

### 第 4 步：安装并配置 Tailscale

1.  **在主节点和 Worker 虚拟机上安装 Tailscale**
    在主节点服务器和 `snake-worker-vm` 虚拟机内部，都执行 Tailscale 的官方安装脚本：

    ```bash
    curl -fsSL https://tailscale.com/install.sh | sh
    ```

2.  **启动并认证 Tailscale**
    在两台机器上都执行以下命令，并根据提示在浏览器中登录同一个 Tailscale 账户进行认证：

    ```bash
    sudo tailscale up
    ```

3.  **获取主节点的 Tailscale IP**
    认证成功后，在 **主节点** 上运行 `tailscale ip -4` 获取其稳定的 Tailscale IP 地址（通常是 `100.x.x.x` 格式）。

> 这里也可以使用 Tailscale 提供的 Magic DNS 服务直接使用主机名作为 hostname，但在 docker 内使用时可能需要配置相关服务的 DNS 为 100.100.100.100。

### 第 5 步：在虚拟机内部署 Worker 服务

现在，虚拟机已经就绪，可以通过 Tailscale 网络与主节点高效通信。

1.  **配置 Worker 的 `.env` 文件**
    在虚拟机内部，按照 **“方案二，Part B”** 的指导准备 Worker 的部署文件。最关键的一步是修改其 `.env` 文件，将所有指向主节点的服务地址（如 `MAIN_NODE_IP` 或 `DB_HOST` 等）设置为**主节点的 Tailscale IP 地址**。

2.  **安装 Docker 并启动 Worker**
    在虚拟机内部安装 Docker 和 Docker Compose，并启动 Worker 服务：

    ```bash
    # 安装 Docker
    sudo apt-get update
    sudo apt-get install -y docker.io docker-compose
    sudo usermod -aG docker $USER
    newgrp docker # 激活 docker 用户组

    # 启动 Worker 服务
    docker compose -f docker-compose.worker.yml up -d
    ```

部署完成后，这个运行在 KVM 虚拟机中的 Worker 节点便可通过高效、安全的虚拟网络接收来自主节点的任务了。