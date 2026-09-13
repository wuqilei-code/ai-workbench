# K8s 沙箱(混合架构)

> 现状采纳 **混合架构**:网关 `app`、`mysql`、`postgres`、`agent-service` 继续跑
> docker-compose;只有每用户 **沙箱(execute/浏览器)** 迁到 K8s Pod。K8s 集群在本机
> 用 **kind**,并让沙箱挂载 **compose 的命名的卷**;浏览器 CDP/MCP 经 **NodePort**
> 打回 compose 网络。全量 K8s(除沙箱外全迁移)见文末备选章节。

## 架构对照

| 组件 | 落点 | 说明 |
|---|---|---|
| 网关 app | compose 容器 | nginx 80 → Spring;CDP/MCP 地址走 NodePort |
| agent-service | compose 容器 | 用 K8s API 管理沙箱 Pod;挂 `kind-kubeconfig.yaml` |
| 业务库/记忆库 | compose (mysql/postgres) | 不变 |
| 每用户沙箱 | kind 集群 Pod `aiwb-sandbox-u<uid>` | K8s API 创建 Pod + NodePort Service |
| 浏览器 | 沙箱 Pod 内常驻 chromium/CDP/MCP | 镜像入口不变 |
| workspace | compose 命名卷 `docker_workspace` | kind 节点 `extraMounts` 挂为 `/mnt/compose-workspace`,Pod 经 hostPath 挂 `/workspace` |

沙箱命名仍为 `aiwb-sandbox-u<uid>`。CDP 端口 9223(容器内 `node cdp-forward.js` 转发
chromium 9222),MCP 端口 8931。

## 拓扑与固定地址

- kind 集群 `kind-aiwb`,节点 `aiwb-control-plane`。
- 节点挂两个网络:`kind`(集群内部网,172.18.0.x)与 **`aiwb-agents`**(与 compose
  网关/agent-service 共网)。节点在 aiwb-agents 上的 IP 已固定为 **172.21.0.200**:
  `docker network connect --ip 172.21.0.200 aiwb-agents aiwb-control-plane`。
- kind-registry 容器(`127.0.0.1:5000`)同时接入 `kind`(节点拉镜像)与 `aiwb-agents`
  (宿主 push/两侧直连)。kind 局域网 IP 由 docker 分配(当前 172.18.0.5)。
- compose 侧到沙箱统一走 `http://172.21.0.200:<nodePort>`(agent-service CDP/MCP、
  沙箱 API 地址)。

**为何用 IP 不用节点名**:chromium 的 CDP HTTP 层校验 `Host` 头,只放行
IP/localhost/MachineName;`aiwb-control-plane:<port>` 会返回 500,`172.21.0.200:<port>`
通过。因此 `AGENT_K8S_SANDBOX_NODE_ADDR` / `APP_SANDBOX_NODE_HOST` 都要配 IP。

## NodePort 规划

`base = 30000`,实例方法 `uid % 2000` 分摊:

| 端口 | 公式 | uid 4242 示例 |
|---|---|---|
| CDP(9223) | `base + uid%2000` | 30242 |
| MCP(8931) | `base + 500 + uid%2000` | 30742 |

Python(`sandbox_k8s.py::_service_port_defs`)与 Java(`BrowserContainerManager`)
公式一致;超 2000 uid 复用端口,靠 Pod IP/namespace 隔离。

## 前置条件 / 一次搭建

```powershell
# 1. kind 集群(定义见 kind-config.yaml;含 extraMounts + containerd mirror)
kind create cluster --name aiwb --config k8s/scripts/kind-config.yaml

# 2. registry 容器(pull/push 通道;cluster recreate 后需重连两网)
docker run -d --name kind-registry --restart unless-stopped -p 127.0.0.1:5000:5000 registry:2
docker network connect kind kind-registry
docker network connect aiwb-agents kind-registry

# 3. 节点入 aiwb-agents 网并固定 IP(每次 cluster recreate 后重做)
docker network connect aiwb-agents aiwb-control-plane
docker network disconnect aiwb-agents aiwb-control-plane   # 清第一次动态分配
docker network connect --ip 172.21.0.200 aiwb-agents aiwb-control-plane

# 4. [重要] kind 重建会重置节点 systemd 代理配置。重写 NO_PROXY 以含 kind-registry,
#    否则 containerd 拉镜像走 http://127.0.0.1:7892 全挂
#    (文件 /etc/systemd/system.conf.d/proxy-default-environment.conf
#     NO_PROXY 需含 kind-registry,kind-registry:5000;改后 systemctl daemon-reload && systemctl restart containerd)
#    现成脚本:k8s/scripts/fix-node-registry.ps1

# 5. 生成 compose 侧 kubeconfig(kind admin.conf → host.docker.internal:<apiserver-port>)
#    k8s/scripts/make-kind-kubeconfig.ps1;port 以 kind get kubeconfig 中 server 端口为准

# 6. 推沙箱镜像(agent 镜像不需要进集群)
docker tag ai-workbench-sandbox:latest 127.0.0.1:5000/aiwb-sandbox:1
docker push 127.0.0.1:5000/aiwb-sandbox:1

# 7. 集群侧只 apply RBAC/命名空间(沙箱 Pod 由 agent-service 按需创建)
kubectl apply -f k8s/manifests/00-namespace.yaml
kubectl apply -f k8s/manifests/21-agent-service.yaml   # SA/ClusterRole,非 Deployment
```

> kind-config.yaml 要点:`containerdConfigPatches` 在 **Cluster 顶层**(节点级语法会
> 报错);节点 `extraMounts: hostPath=/var/lib/docker/volumes/docker_workspace/_data`,
> `containerPath=/mnt/compose-workspace`。沙箱 Pod 的 hostPath 根是 `/mnt/compose-workspace`,
> 挂 `/workspace`(不带 subPath;目录 `users/<uid>/project` 由 kubelet 以
> `DirectoryOrCreate` 保证,权限 `fsGroup 10001` 交给集群)。

## 沙箱 Pod 生命周期(混合版)

- **ensure**:`POST /api/users/{uid}/sandbox/ensure`(带 `X-Internal-Token`)→
  agent-service 建 NodePort Service + Pod,120s 内等 Running,写 `.sandbox-probe`
  校验 hostPath 数据互通。
- **数据同步**:`upload/download` 走 compose 已挂载的被创建卷 `/shared-ws`
  (= `docker_workspace` = 沙箱 `/workspace`),字节级一致,免 put/get_archive。
- **execute**:`K8sSandbox.execute` 走 `kubernetes.stream` exec(`timeout` 包装 +
  尾哨取真实退出码)。
- **回收/停止**:沿用 docker 版 reaper(`AGENT_SANDBOX_IDLE_TTL`);stop 删 Pod。
- 网关 `BrowserContainerManager`:沙箱地址 `app.sandbox-addr-mode=nodeport` 时按
  `agent-base + uid%2000` 拼 CDP/MCP URL(直连 NodePort),不再走集群 DNS。

## 运行

```bash
docker compose -f docker/docker-compose.yml up -d     # mysql/postgres/app/agent-service
# agent-service env 关键项:
#   AGENT_SANDBOX_BACKEND=k8s
#   AGENT_SANDBOX_IMAGE=kind-registry:5000/aiwb-sandbox:1
#   AGENT_K8S_NAMESPACE=ai-workbench
#   AGENT_K8S_SANDBOX_VOLUME_TYPE=hostpath
#   AGENT_K8S_SANDBOX_VOLUME_HOST_PATH=/mnt/compose-workspace
#   AGENT_K8S_SANDBOX_NODE_ADDR=172.21.0.200
#   AGENT_K8S_SANDBOX_NODEPORT=true / _NODEPORT_BASE=30000
#   AGENT_K8S_API_HOST=host.docker.internal:<apiserver-port> (经 insecure-skip-tls-verify)
#   KUBECONFIG=/kube/config(挂载 kind-kubeconfig.yaml)
# app env 关键项:
#   APP_SANDBOX_ADDR_MODE=nodeport / APP_SANDBOX_NODE_HOST=172.21.0.200 / APP_SANDBOX_NODEPORT_BASE=30000
```

## 验证

```bash
kubectl -n ai-workbench get pod aiwb-sandbox-u<uid>          # 应 Running
# 从 compose 侧(agent-service 内)直连沙箱 CDP(应 200 + chromium 版本 JSON):
python -c "import urllib.request;print(urllib.request.urlopen(
  'http://172.21.0.200:<cdp-nodeport>/json/version').status)"
# execute 往返:
docker exec docker-agent-service-1 python -c \
  "from app.sandbox import get_sandbox;print(get_sandbox('42').execute('echo hi from pod',timeout=30))"
```

## 常见问题

- **create pod 报 containerPort Required**:容器 ports 用 `_container_port_defs`
  (containerPort 9223/8931),Service 端口定义勿复用。
- **拉镜 40x/Refused**:节点 NO_PROXY 未含 kind-registry(见步骤 4);或 registry 容器
  未接入 `kind` 网络。
- **CDP 500**:Host 头写了节点名改为 IP(见上文拓扑节)。
- **apiserver 端口变了**:kind 重建后 `make-kind-kubeconfig.ps1` 重新生成 + compose
  env `AGENT_K8S_API_HOST`/挂载文件同步。

## 备选:全量 K8s 迁移

早期文档(`02-configmap.yaml` / `10..12` / `20-app.yaml`)描述把除沙箱外的
mysql/postgres/app/agent-service 全部迁入集群,沙箱 headless Service + RWX PVC。
当前混合架构下这些清单未启用,如需可导航 git 历史查看此前版本说明。