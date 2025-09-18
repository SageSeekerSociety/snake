# 中国人民大学蛇王争霸赛比赛系统

[![Build Projects](https://github.com/SageSeekerSociety/snake/actions/workflows/build.yml/badge.svg)](https://github.com/SageSeekerSociety/snake/actions/workflows/build.yml)

## 部署方法
本项目采用 docker compose 部署，首先需要安装 docker 和 docker-compose。

使用以下命令启动项目：
```shell
git clone https://github.com/SageSeekerSociety/snake
cd snake/docker-compose-deploy
cp sample.env .env
docker compose up
```

注意，在生产环境中部署前必须要修改 `.env` 文件中的 `JWT_SECRET` 和 `DB_PASSWORD` 为随机字符串。这是至关重要的，否则会导致安全问题。
此外，您需要正确设置`EMAIL_*`相关的环境变量，以便系统能够发送邮件。

更详细的部署方法可以参考[部署文档](docs/deployment.md)

## 开发

参见当前代码库的结构[文档](docs/codebase.md)

启动后各个服务的路径如下：
| 服务 | 路径 |
| --- | --- |
| Cheese Auth Server | /api/cheese-auth |
| Sandbox Server | /api/sandbox |

