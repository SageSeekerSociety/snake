# **贪吃蛇算法竞技平台后端系统交接文档**

## **系统概览**

本项目是一个支持多人对战“贪吃蛇”游戏的分布式算法竞赛平台后端。后端采用Spring Boot (Kotlin)构建，整体架构由Controller（控制节点）和Worker（执行节点）两个主要服务模块组成，借助RabbitMQ消息队列解耦通信。平台还依赖PostgreSQL数据库持久化存储、Redis缓存游戏状态、MinIO对象存储源代码/编译产物，并集成了Cheese-Auth模块和学校提供的 OAuth 服务进行用户认证（通过 JWT 验证接口访问权限）。整个服务通过 Nginx 反向代理对外提供统一入口。在可观测性方面，系统接入了 Prometheus/Grafana（指标监控）、Loki（日志收集）以及 Jaeger（分布式追踪）以监控运行状况。

**系统架构特点**：后台不直接维护游戏的完整运行状态，而是将主要状态管理逻辑留在前端：前端每个游戏回合（tick）收集必要的环境输入、对战信息，并在请求算法执行时将这些信息（例如当前局的会话ID、tick编号、各玩家输入等）传给后端。后端的职责是在隔离的沙箱环境中编译并运行用户算法，并返回结果（如移动方向等），而不涉及游戏规则判断。这种前后端分离设计简化了后端逻辑，提升了可扩展性。

## **模块与代码结构**

后端代码主要划分为三个模块：

* **backend-common**：公共模块，包含后端共享的数据模型、数据库实体和仓库接口、以及通用的工具类和常量定义。例如，定义了 `CompilationJob`、`ExecutionJob` 等 JPA 实体用于记录编译和执行任务的状态；提供了消息队列用的常量（如交换机、路由键名称）和工具方法（如 UUIDv7 生成、日志辅助等）。该模块被 Controller 和 Worker 共用，确保两端在数据结构和协议上保持一致。
* **backend-controller**：控制器模块，是后端的REST API 服务。它负责接收前端请求（代码提交、执行任务等），与数据库交互管理任务状态，并通过 RabbitMQ 将任务分发给 Worker 执行。Controller 包含若干 Spring MVC 控制器（如 `CompileController`、`ExecuteController`）以及对应的服务类 ：
    * **JobSubmitService**：封装任务提交逻辑。当收到编译或执行请求时，该服务将请求信息存入数据库并封装成消息投递到 RabbitMQ 对应队列。例如，提交代码编译时，生成一个 `CompilationJob` 记录（状态初始为PENDING）并发送 `CompilationRequest` 消息到编译任务队列。
    * **ResultListener**：这是运行于 Controller 内的 RabbitMQ 消息监听器，异步订阅 Worker 返回的结果通知队列。它采用回调Flow + 协程实现高效的消息消费，手动ACK机制保证可靠处理。收到编译或执行结果消息后，ResultListener 将更新数据库任务状态，并进一步进行后续处理（例如更新玩家记录、缓存失效广播、推送SSE事件等，见下文）。
    * **JobFlowService**：维护任务的Server-Sent Events（SSE）事件流。对每个任务或每个游戏对局(session)，JobFlowService 持有一个Reactive Flow，用于收集该任务/对局的各类事件（提交、运行结果等），供 SSE 控制器输出给前端。
    * **其他**：如 `JobQueryService`（提供任务查询/结果封装）、`PlayerUpdateService`（玩家信息更新，如编译成功后记录程序引用）等。接口文档的权威来源是本交接文档的“后端主要接口说明”章节以及代码中Controller层的相关注释。
* **backend-worker**：工作节点模块，负责实际执行编译和运行。Worker 进程通过 RabbitMQ 订阅任务队列，实现为Spring Boot的 `@RabbitListener`（见 `TaskListenerService`）来异步接收来自 Controller 的编译/执行请求消息。Worker 收到任务后，会调用相应服务处理 ：
    * **CompileService**：编译服务，按请求拉取源代码并调用系统编译器进行编译。编译流程包括：从 MinIO 下载源文件到本地临时目录；调用预配置的编译器命令（默认 clang++，参数如 C++17 标准、O2优化等）执行编译；监控编译过程（默认超时60秒），收集编译输出。编译完成后，若成功则将生成的可执行文件上传回 MinIO 对象存储并更新 `CompilationJob` 状态为 SUCCESS，否则记录错误信息状态为 FAILED/ERROR。最后，通过 `ResultNotifier` 将编译结果以消息形式发送给 Controller。
    * **ExecuteService**：执行服务，在沙箱中运行用户算法。执行流程更为复杂（详见后文“执行流程”），概括而言：Worker 从缓存/存储获取用户编译后的程序二进制，准备运行所需的输入（包括前端传来的环境输入以及上一次算法运行后存储的内存状态），然后利用 nsjail 沙箱限制资源运行用户程序，收集输出、耗时、内存占用等结果数据。执行完毕后，Worker 更新 `ExecutionJob` 状态和结果，并通过消息将执行结果发送回 Controller。
    * 此外，`backend-worker` 模块还包含`CacheManager`（程序二进制缓存管理）和与 RabbitMQ 通信的配置类、监听器等：如 `CacheEvictionListener` 用于接收缓存失效通知、`ResultNotifier` 用于发布结果消息等。

## **核心工作流程**

下面分别介绍代码编译和算法执行两大流程，以及其中的缓存和状态管理机制。

### **1. 代码编译流程**

代码编译流程主要涉及 Controller 的提交接口和 Worker 的 `CompileService`，协同完成用户提交源代码的编译。具体步骤如下：

1.  **提交编译请求 (Controller)**：用户通过前端界面上传源代码文件，前端调用 Controller 提供的编译API接口（`POST /compile`）。Controller 首先验证用户权限（需登录且具备代码提交资格），然后读取上传的文件流。为了防止比赛截止后滥投，系统支持提交截止时间和白名单检查：若当前时间超过比赛关闭时间且用户不在白名单，则拒绝提交。通过验证后，Controller 调用 `JobSubmitService` 的 `submitCompilation` 方法处理请求。
2.  **存储源代码 & 创建任务记录**：`JobSubmitService` 为每次提交生成一个全局唯一的 Job ID（采用UUIDv7算法）并获取当前时间作为提交时间。随后，它构造 MinIO 对象存储键，例如 `"sources/<userId>/<jobId>/source.cpp"`，并将用户上传的源代码文件流保存到 MinIO 中。如果文件上传失败，流程会中止并返回错误。源代码成功存储后，系统在数据库中新建一条 `CompilationJob` 记录，填入Job ID、提交用户ID、状态PENDING、提交时间及源码存储引用（MinIO键）等信息。数据库操作采用事务保证一致性，若插入失败会回滚并删除已上传的MinIO文件。插入成功后，`CompilationJob` 状态此时仍为 PENDING，表示任务等待处理。
3.  **发送编译任务消息**：接下来，Controller 将编译任务派发给 Worker。`JobSubmitService` 会创建一个 `CompilationRequest` 消息对象，其中包含必要的信息：jobId、userId、sourceCodeRef（源码存储键）和时间戳等。然后通过 RabbitMQ 将此消息发送到编译任务队列（名称默认`oj.compile.tasks`），对应的交换机和路由键在配置中定义。发送时还设置了消息属性如 CorrelationId（设为Job ID）以及自定义头部标识消息类型为编译请求。至此，Controller 在HTTP层面返回接受(202)响应，告知前端编译任务已提交成功，并携带任务ID供后续查询或建立SSE连接。与此同时，任务消息已进入消息队列等待Worker处理。
4.  **Worker 拉取编译任务并执行**：运行中的 Worker 通过 RabbitMQ 监听编译队列，`TaskListenerService` 收到新消息时会异步触发处理流程。Worker 提交一个异步任务到其协程Scope中，由 `CompileService` 处理该 `CompilationRequest`。`CompileService` 首先在Worker本地为本次编译创建隔离的临时目录（如 `/tmp/snake/<userId>/compile/<jobId>`），用于存放源文件和编译输出。接着，它根据请求中提供的 MinIO键下载源代码文件到该目录下，保存为固定文件名（如 `source.cpp`）。如果下载失败（例如存储服务不可用），则记录错误、将Job标记 ERROR 并抛出异常通知 RabbitMQ NACK，该任务消息可进入死信队列供管理员排查。
5.  源文件成功落地后，`CompileService` 调用系统编译器对源代码进行编译：默认使用 clang++，参数包括 `-std=c++17`, `-O2` 优化, 链接线程/数学库等，可以在配置中调整。编译通过 `ProcessBuilder` 以子进程方式执行，并将编译输出（stdout/stderr合并）实时读取缓冲。系统设置了编译超时时间，默认为60秒，若超时未完成则强制终止编译进程并标记任务超时失败。
6.  编译子进程退出后，Worker 检查其退出码：为0则表示编译成功，否则视为失败。对于编译失败的情况，系统将退出码和最多前1024字符的编译输出存入 `CompilationJob` 的 `compilerOutput` 字段，并将状态置为 FAILED，以供前端查看错误信息。编译成功时，生成了可执行二进制文件（约定输出名为“program”）。Worker 随即将此文件上传到 MinIO 对象存储的专属路径，例如 `"programs/<userId>/program"`。若上传失败则视为存储错误，将任务标记 ERROR；若上传成功，则获取到对象键（compiledProgramRef）并保存到 `CompilationJob` 的对应字段。此时任务状态置为 SUCCESS。
7.  **结果返回与缓存更新**：`CompileService` 在最终进入 `finally` 块时，将任务最终状态以及编译输出、错误详情等一次性更新到数据库记录。接下来，Worker 通过 `ResultNotifier` 发布编译结果通知消息到 RabbitMQ。结果消息通常发送到结果交换机（默认`oj.results.exchange`），由 Controller 的结果监听器订阅处理。消息内容为 `CompilationResultNotification`，包括 Job ID、用户ID、编译状态、编译产物存储键（若成功）等。
8.  Controller 端的 `ResultListener` 会从结果队列取出该消息进行处理。如果编译成功，则调用 `PlayerUpdateService`，将对应用户标记为活跃选手并更新其“当前可执行程序”引用为新的 MinIO 对象键，同时记录最后成功编译时间和JobID。这一步保证每个用户始终关联其最近一次成功编译的程序，用于比赛对战。
9.  **缓存失效广播**：由于各 Worker 节点可能缓存了用户旧的程序二进制，为确保后续执行使用最新编译结果，Controller 在检测到编译成功后，会通过 Fanout 广播发送缓存清理通知。`ResultListener` 调用 `CacheEvictionPublisher`，将包含编译产物键的 `CacheEvictMessage` 广播到预定义的缓存失效交换机。所有运行中的 Worker 都订阅了这个 Fanout，因此每个 Worker 都会收到一份失效通知并调用本地 `CacheManager` 将对应缓存条目删除。
10. **SSE推送**：最后，Controller 将编译任务的最终结果通过 SSE实时推送给前端。`ResultListener` 会查询数据库获取完整的 `CompilationJob` 结果数据，封装成 `JobSseEvent` 事件。`JobFlowService` 随即将该事件发布到对应任务的事件Flow中。如果前端已建立SSE连接监听该任务，则会立即收到包含最终结果的事件，从而完成整个编译流程闭环。

**小结**：编译流程自提交到结果返回涉及Controller和Worker的紧密配合。借助消息队列实现异步处理和解耦，使得编译请求可以被多个Worker并行处理，提高系统吞吐。利用MinIO存储源代码和二进制文件，避免了直接通过消息传递大文件的低效行为。

### **2. 算法执行流程**

执行流程用于在游戏进行过程中每个回合调用用户算法产出动作决策。该流程涉及前端批量提交、Controller 分发任务、Worker 沙箱执行，以及结果汇聚发送。

1.  **批量提交执行请求 (Controller)**：前端在每个游戏 tick 结束时，收集本tick所有需要运行的玩家算法请求，并通过调用后端接口 `POST /execute/batch` 一次性提交。`ExecuteController` 会校验并标准化请求，为每个请求分配一个全局唯一的 `sessionId`（若未提供）。然后调用 `JobSubmitService` 处理该批请求。
2.  **分发任务 (Controller)**：`JobSubmitService` 遍历请求列表，为每个执行请求创建 `ExecutionJob` 数据库记录（初始状态为 PENDING），并通过 `JobFlowService` 发布一个 “SUBMITTED” 事件。接着，构造 `ExecutionRequest` 消息，包含执行所需的全部信息（Job ID、用户ID、输入数据、资源限制等），并通过 RabbitMQ 投递到执行任务队列。处理完整个列表后，`ExecuteController` 将各请求的初始提交状态汇总返回给前端。
3.  **Worker 拉取并处理任务**：Worker 进程通过 `TaskListenerService` 监听执行队列。收到新消息后，调用 `ExecuteService` 的 `processExecutionRequest` 方法处理。
4.  **程序缓存检查**：`ExecuteService` 首先通过 `CacheManager` 检查本地是否有用户程序的缓存。若无或已失效，则从 MinIO 下载最新的已编译程序二进制文件到本地缓存目录，以备执行。这减少了频繁从存储获取文件的开销。
5.  **准备执行环境**：Worker 为本次执行创建隔离的运行目录，并准备所需文件：
    * 将缓存中的程序二进制复制到执行目录下，命名为 `program` 并设为可执行。
    * 生成输入文件 `input.txt`：Worker 将 `ExecutionRequest` 携带的本回合输入数据（`inputData`）与该玩家在上一tick存储的内存数据拼接后写入。内存数据从 Redis 中获取（键格式 `session:<sessionId>:memory:<userId>:<prevTick>`），若存在则解码后附加到当前输入的末尾。
6.  **沙箱执行用户算法**：平台使用**定制版 nsjail** 作为安全沙箱。ExecuteService 在启动 nsjail 前，会先获取一个并发许可：由于同一台机器资源有限，Worker 使用信号量（Semaphore）限制同一时刻并发运行的 nsjail 实例数量，具体限制可通过 `application.concurrency.nsjail-permits` 配置（建议配置为匹配当前机器 CPU 核数稍低的值）。如果当前已有大量任务在跑，其余任务需要等待许可。获取到许可后，Worker 构造 nsjail 执行命令并启动子进程：
    * Worker **直接**从 nsjail 子进程的标准输出（stdout）和标准错误（stderr）管道中读取用户程序的输出。
    * **资源统计**：我们维护的定制版 nsjail 在 cgroup v2 模式下，会在程序结束时向其自身的日志文件（`nsjail.log`）输出一行资源统计摘要，形如 `Cgroup Stats: CPU_usec=<...> MEM_peak_bytes=<...>`。Worker **仅从该日志文件解析这一行以获取 CPU 和内存峰值等指标**。
    * Worker 等待 nsjail 子进程结束，若超时则强制终止。之后，根据 nsjail 的退出码、资源使用统计以及用户程序的输出，判断任务执行状态（SUCCESS 或各种失败类型）。
7.  **处理程序输出与记忆（Memory）**：
    * Worker 从用户程序的 `stdout` 中，按约定提取决策输出（第一行）和新的“记忆”数据（第二行及以后）。
    * **记忆载荷格式**：**算法程序输出的记忆内容（第二行及以后）可以是任意字节序列或文本**，平台不做格式限制。
    * **持久化与校验**：Worker 在将记忆数据写入 Redis 前，会先**统一进行 Base64 编码**。同时，会校验**原始字节大小**是否超限（默认4KB），若超限或 Base64 编码失败，则**丢弃该记忆**，将任务标记为特定状态（非致命错误，不影响本次动作输出），并记录日志。有效的记忆数据以 Base64 字符串形式存入 Redis，键格式为 `"session:<sessionId>:memory:<userId>:<tickNumber>"`，并设置过期时间（默认15分钟），供下一回合使用。从 Redis 读取时，会再进行 Base64 解码还原为原始字节。
8.  **结果收集与通知**：执行完毕后，`ExecuteService` 将执行结果（状态、动作输出、资源消耗、新的记忆数据等）封装成 `ExecutionResultNotification` 对象，通过 RabbitMQ 发送回 Controller。
9.  **Controller 汇总并推送结果**：Controller 端的 `ResultListener` 收到结果消息后，将其转换为前端需要的SSE事件格式（`JobSseEvent`，类型为 "FINAL_RESULT"），并通过 `JobFlowService` 发布到对应游戏会话（session）的事件流中。前端通过订阅该 SSE 流，即可实时获取该局所有玩家在本回合的算法运行结果，并更新游戏状态。为保护隐私，在推送给前端前，**非本人算法的记忆数据字段会被移除**。

**状态管理机制**：后端通过 Redis 实现算法记忆状态在回合间的暂存。选手算法可以在每次输出动作的同时，输出一段状态数据，平台在下一次调用时原样提供给程序，实现状态延续。该过程对前端透明。

### **3. 程序缓存机制**

后端 Worker 针对编译产物实施了本地缓存，以减少存储IO和提高执行速度。当 Worker 首次执行某用户程序时，会从 MinIO 下载其二进制文件并缓存在本地。后续执行时，若缓存未过期，则直接使用本地文件。当用户重新编译成功后，Controller 会广播缓存失效消息，通知所有 Worker 删除旧缓存，确保后续执行使用最新版本。

**缓存预热与冷启动问题**：当前缓存机制存在一个潜在的性能问题，即当某个用户的算法首次被调度到一台特定的 Worker 节点执行时，该 Worker 由于本地缓存缺失，必须从 MinIO 同步拉取程序二进制文件，这会导致该次执行的延迟显著高于后续有缓存的执行。在 Worker 节点数量较多的情况下，这个问题会愈发明显，因为任务被调度到“冷”节点的概率增加。为了优化在关键场合（例如正式比赛、决赛）的响应速度，可以考虑引入缓存**主动预热**机制。例如，在比赛开始前，由 Controller 触发一个指令，让所有 Worker 节点提前将所有参赛选手的最新编译产物下载到本地缓存中，从而消除首次执行时的“冷启动”延迟。

## **后端主要接口说明**

后端 Controller 模块提供了一系列 HTTP API。以下是主要接口说明：

* **提交源码编译**: `POST /compile`
    接受表单上传的源代码文件（字段名`sourceFile`）。接口立即返回`202 Accepted`，携带`jobId`用于标识任务。客户端可随后通过 SSE 或查询接口获取编译结果。
* **监听编译结果**: `GET /compile/stream/{jobId}`
    建立一个 Server-Sent Events 长连接，实时接收指定 Job 的编译状态事件流。
* **批量提交执行任务**: `POST /execute/batch`
    请求体为JSON数组，每个元素包含一个执行任务描述，包括：`userId`（算法所属用户 ID）、`inputData`（传入算法的输入）、可选 `tickNumber`（当前 tick 编号）和可选 `clientRequestId`（客户端自定义ID用于结果对应）等。所有请求项应属于同一 `sessionId`（对局ID）；如果未提供则由服务器自动生成返回。接口返回`202 Accepted`，内容包含 `sessionId` 以及每项请求的初始提交状态。实际执行结果稍后通过 SSE 推送。该接口受频率限制控制（配置了请求节流注解），以防止恶意高频调用。
* **监听对局执行结果**: `GET /execute/stream/{sessionId}?fromTick=<N>`
    建立SSE连接，用于订阅指定对局(session)的所有执行任务结果事件流。
* **获取所有选手列表**: `GET /submitters`
    需要管理员权限，返回所有有提交记录的用户信息列表。
* **导出最新源码汇总**: `GET /compile/export/latest-sources.zip`
    需要管理员权限，打包所有用户最后一次成功编译的源代码为ZIP文件下载。

除了上述主要接口外，后端还通过 Spring Actuator 暴露了健康检查和指标接口（如 `/actuator/health`、`/actuator/prometheus`），供运维和监控系统使用。**注意**：绝大多数接口都需要通过 Cheese-Auth 模块进行认证，并在请求头中附带 JWT Token。后端通过注解控制访问权限，未认证或权限不足将被拦截。

## **部署与扩展指南**

项目提供了 Docker Compose 模板（`snake/docker-compose-deploy/docker-compose.yml`）方便一键部署所有服务组件，包括controller, worker, Cheese-Auth, RabbitMQ, PostgreSQL, Redis, MinIO 及可观测性套件。使用时需先在 .env 或对应环境变量中配置各服务连接信息，如数据库和消息队列的地址账号等（Compose 已提供 sample.env 示例）。特别地，需设置  JWT 签名密钥 (`JWT_SECRET`)、数据库密码等敏感信息。启动 Compose 后，Nginx 将在 `${FINAL_PORT}` 端口（默认 80）监听，将 /api/sandbox/* 的请求转发给后端 Controller（Spring Boot 默认为 8080），/api/cheese-auth/* 转发给认证服务，其余静态请求服务前端页面。管理员可以通过 Grafana 面板监视各项指标，通过 Loki 查询日志，实现对运行状态的全面观测。

### **运行环境前置要求**

* **启用 cgroup v2**：Worker 节点宿主机内核必须启用 cgroup v2（统一层级模式）且版本需支持 `memory.peak`（推荐 **Linux ≥ 5.15**）。
* **容器权限**：在 Docker/K8s 场景下，需确保 Worker 容器对 cgroup v2 可见。常见做法是传递 `--cgroupns=host` 参数（或 Compose 中配置 `cgroup: host`）、宿主机 systemd 开启统一层级，并为容器授予执行 nsjail 所需的 `privileged` 设置（保持与现有 compose 配置一致，或是配置适当的 capabilities）。

### **水平扩展**

* **Worker节点**：Worker 是无状态的，可以通过增加副本来提高并发处理能力。只需在新机器上启动额外的 Worker 容器实例（推荐做法是使用与主 Compose 几乎相同的配置在新机器上启动额外的 Worker 容器实例以及对应的 promtail 日志收集），并将其连接指向主节点的 RabbitMQ、Redis、MinIO 等服务即可自动分担任务负载。
    * **同构要求**：在水平扩展 Worker 节点时，一个关键的限制是所有 Worker 实例必须部署在具有相同 CPU 体系结构的机器上。例如，如果生产环境使用 x86-64 架构的服务器，那么所有新增的 Worker 节点也必须是 x86-64 架构。这是因为用户的源代码由某一个 Worker 编译后生成的二进制可执行文件，会存储于 MinIO 并在后续执行时分发给其他 Worker。如果集群中存在异构CPU（如同时有 x86 和 ARM 架构的节点），那么在一个节点上编译的产物将无法在另一种架构的节点上运行，导致执行失败。
* **Controller节点**：**不建议在当前架构下直接水平扩展 Controller**。当前设计中 Controller 内部维护着对结果队列的唯一消费者和各 SSE Flow，多实例可能导致结果消息抢占和 SSE 推送错乱。若必须扩展，需考虑以下两种路线：
    * **路线 A：黏性会话 + 单活 Flow（最小改动）**
        * 前端 SSE 连接通过 `sessionId` 哈希做 Nginx/Ingress **黏性路由**，确保同一会话的请求命中同一 Controller 实例。
        * 每个 Controller 只消费结果队列的子集（通过队列分片或路由键分片）。
        * 若发生断连，客户端可从 `ExecutionJob` 表中回溯最近 N tick 的结果来补全数据。
    * **路线 B：中心化“结果总线” + 共享订阅（架构升级）**
        * 结果消息进入一个 Fanout/Topic 类型的总线（如 RabbitMQ Fanout 或 Kafka Topic），所有 Controller 实例**共享消费**，并将事件投递到共享的流式存储中（如 Redis Stream 或 Kafka）。
        * SSE 服务仅从共享流中读取数据，任一 Controller 实例都能“随取随播”，实现真正的无状态水平扩缩容和断点续播。
        * 此方案下，`ExecutionJob` 表仅用作审计与离线分析。
        
### **依赖服务扩展**

对于 RabbitMQ、PostgreSQL、Redis、MinIO 等基础组件，如果需要容灾或更高性能，可采用各自官方推荐的集群/高可用部署方式，后端程序只需在配置中提供正确的连接地址即可兼容。
