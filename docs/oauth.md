### **第三方认证 (OAuth) 配置指南**

本文档为“蛇王争霸”比赛系统提供第三方 OAuth 2.0 认证的配置和实现说明。

-----

### **通用接入步骤**

集成一个新的 OAuth Provider，需遵循以下步骤：

#### **1. 创建 Provider 插件**

在部署目录下，创建一个与 Provider ID同名的**目录**。例如，若 Provider ID 为 `my-provider`，则创建 `my-provider/` 目录。在该目录中，必须包含一个名为 `index.js` 的入口文件。

  * **路径**: `docker-compose-deploy/plugins/oauth/<provider-id>/index.js`

`index.js` 文件必须导出一个工厂函数（如 `createProvider` 或 `default`），该函数接收客户端凭据并返回一个符合 `OAuthProvider` 接口规范的对象。

#### **2. 配置环境变量**

在部署目录的 `.env` 文件中，添加并配置以下环境变量：

1.  **启用 Provider**: 将 Provider 的 ID 添加到 `OAUTH_ENABLED_PROVIDERS` 列表中（多个用逗号`,`分隔）。
2.  **配置凭据**: 添加该 Provider 的 `CLIENT_ID`、`CLIENT_SECRET` 和 `REDIRECT_URL`。

```env
# .env

# 启用提供商，多个用逗号隔开
OAUTH_ENABLED_PROVIDERS=ruc,my-provider

# ... 其他已有配置 ...

# My Provider 的配置
OAUTH_MY-PROVIDER_CLIENT_ID="your_my-provider_client_id"
OAUTH_MY-PROVIDER_CLIENT_SECRET="your_my-provider_client_secret"
OAUTH_MY-PROVIDER_REDIRECT_URL="https://your-domain.com/api/cheese-auth/users/auth/oauth/callback/my-provider"
```

#### **3. 重启服务**

保存 `.env` 文件后，在 `docker-compose-deploy` 目录下执行以下命令重启服务：

```shell
docker compose down && docker compose up -d
```

-----

### **实例：接入微人大 (RUC) 认证**

#### **特别说明**

**由于涉及中国人民大学信息安全政策与规定，微人大（RUC）认证 Provider 的具体实现代码不便开源。** 如需在部署时启用此功能，**请联系项目的前任维护者以获取完整的 `ruc` 插件目录**。

#### **配置步骤**

1.  **放置插件目录**:
    将获取到的整个 `ruc` 目录放置到以下路径：
    `docker-compose-deploy/plugins/oauth/ruc/`

2.  **配置环境变量**:
    在 `.env` 文件中，确保 `OAUTH_ENABLED_PROVIDERS` 包含了 `ruc`，并添加 RUC 认证所需的凭据变量。

    ```env
    # .env
    OAUTH_ENABLED_PROVIDERS=ruc
    OAUTH_RUC_CLIENT_ID="your_ruc_client_id"
    OAUTH_RUC_CLIENT_SECRET="your_ruc_client_secret"
    OAUTH_RUC_REDIRECT_URL="https://your-domain.com/api/cheese-auth/users/auth/oauth/callback/ruc"
    ```

3.  **重启服务**。

-----

### **OAuth 登录/注册流程说明**

本系统的 OAuth 流程旨在无缝地为用户完成登录或注册，其核心逻辑（位于 `loginWithOAuth` 方法）如下：

当用户通过第三方 OAuth 提供商（例如，微人大）成功授权并返回到本系统后，后端会根据提供商返回的用户信息执行以下自动化流程：

1.  **查找 OAuth 关联记录**:
    系统首先会检查 `UserOAuthConnection` 数据表，看是否存在该 **OAuth 提供商 ID** (`providerId`) + **用户在提供商的唯一 ID** (`providerUserId`) 的记录。

      * **如果找到**，并且该记录关联的本地用户账户是**激活状态**，系统将直接为该用户登录。这是最快的路径。

2.  **通过邮箱查找用户**:

      * **如果没有找到 OAuth 关联记录**，系统会获取用户在 OAuth 提供商处的 **邮箱地址**。
      * 然后，系统会使用该邮箱在本地 `User` 数据表中查找是否存在一个**激活状态**的账户。

3.  **自动关联账户**:

      * **如果通过邮箱找到了一个已存在的本地账户**，系统会认为这是同一个人。
      * 此时，系统会自动在 `UserOAuthConnection` 表中创建一条新记录，将这次的 OAuth 登录信息与这个已存在的本地账户**进行关联**。
      * 关联成功后，为该用户登录。未来该用户再通过此 OAuth 方式登录，将直接通过步骤 1 完成。

4.  **创建新用户**:

      * **如果以上步骤均未找到匹配的账户**，系统将为该用户**自动注册**一个新账户。
      * **用户名生成**: 系统会尝试使用 OAuth 返回的 `preferredUsername` 或 `name` 生成一个唯一的用户名。如果用户名已存在，会自动添加后缀（如 `_1`, `_2`）以确保唯一性。
      * **密码安全**: 系统会为新账户生成一个**高强度的随机密码**。这个密码用户本人不会知道，因为他将始终通过 OAuth 方式登录。
      * **账户创建**: 系统在一个数据库事务中，原子性地创建 `User`、`UserProfile` 和 `UserOAuthConnection` 三条记录。
      * 注册完成后，为这个新创建的用户登录。

> 该流程与标准的 OAuth 最佳实践不甚一致，但对于我们的蛇王争霸系统的需求来说 it's good enough

-----

### **禁用用户名/密码登录**

在某些场景下，可能需要强制所有用户都通过指定的 OAuth 方式登录，例如在校内比赛中只允许通过微人大认证。本系统支持通过环境变量禁用传统的用户名和密码登录方式。

要实现此功能，请在 `.env` 文件中设置以下变量：

```env
# .env

# 设置为 false 来禁用用户名密码登录，只显示 OAuth 按钮
# 设置为 true 或不设置此行，则同时允许两种登录方式
ENABLE_USERNAME_LOGIN=false
```

当 `ENABLE_USERNAME_LOGIN` 被设置为 `false` 时，前端登录页面将**不会渲染**用户名和密码的输入框，仅显示已配置的 OAuth 提供商的登录按钮。