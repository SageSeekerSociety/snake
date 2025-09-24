# 中国人民大学蛇王争霸赛比赛系统

[![Build Projects](https://github.com/SageSeekerSociety/snake/actions/workflows/build.yml/badge.svg)](https://github.com/SageSeekerSociety/snake/actions/workflows/build.yml)

## 部署方法
本项目采用 Docker Compose 部署，首先需要安装 docker 和 docker-compose。

使用以下命令启动项目：
```shell
git clone https://github.com/SageSeekerSociety/snake
cd snake
git submodule update --init --recursive
cd docker-compose-deploy
cp sample.env .env
docker compose up
```

注意，在生产环境中部署前必须要修改 `.env` 文件中的 `JWT_SECRET` 和 `DB_PASSWORD` 为随机字符串。这是至关重要的，否则会导致安全问题。
此外，您需要正确设置`EMAIL_*`相关的环境变量，以便系统能够发送邮件。

更详细的部署方法可以参考[部署文档](docs/deployment.md)

启动后各个服务的路径如下：
| 服务 | 路径 |
| --- | --- |
| Cheese Auth Server | /api/cheese-auth |
| Sandbox Server | /api/sandbox |

## 前端子模块
本仓库的 `frontend/` 目录链接到独立的前端仓库 [`snake-frontend`](https://github.com/SageSeekerSociety/snake-frontend)。

- 首次克隆时请使用 `git clone --recurse-submodules`，或在仓库根目录运行 `git submodule update --init --recursive`。
- 如果需要同步前端仓库的最新提交，在仓库根目录运行 `git submodule update --remote frontend`。
- 修改前端代码时请在 `frontend/` 目录内按前端仓库的流程单独提交和推送。

## 开发

参见当前代码库的结构[文档](docs/codebase.md) 以及前端代码库的[文档](docs/frontend.md)
