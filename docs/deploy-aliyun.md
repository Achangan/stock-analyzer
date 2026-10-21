# stock-analyzer 阿里云部署操作手册

> 适用版本：主仓库 `6dccfcf`（微服务版，5 个服务 + Nacos + MySQL + Python 采集）
> 目标环境：阿里云 ECS（Ubuntu 22.04 LTS），单机部署
> 阅读方式：第一次部署按顺序执行；已有环境直接从第 5 节开始

---

## 0. 部署前须知

### 0.1 架构与端口

```
浏览器
  │  80 / 443
  ▼
Nginx ──静态文件──> /var/www/stock-analyzer (前端 dist)
  │  /api/ 反向代理
  ▼
gateway-service :8080
  ├─ lb://quote-service      :8081   指数/个股查询
  ├─ lb://analysis-service   :8082   指标/因子/回测
  ├─ lb://report-service     :8083   报告/知识库/定时流水线
  └─ lb://portfolio-service  :8084   自选/预警/持仓

Nacos :8848（注册中心）
MySQL :3306（stock_analyzer 库）
Python venv（Baostock/Tushare 采集，被 report-service 以子进程调用）
```

| 端口 | 用途 | 是否对公网开放 |
| --- | --- | --- |
| 80 / 443 | Nginx | 是 |
| 22 | SSH | 仅自己的 IP |
| 8080 | 网关 | 否，仅 127.0.0.1 |
| 8081-8084 | 业务服务 | 否 |
| 8848 / 9848 | Nacos | 否 |
| 3306 | MySQL | 否 |
| 5174 | Vite 开发服务器 | 不部署 |

### 0.2 机器规格建议

| 规格 | 适用场景 | 说明 |
| --- | --- | --- |
| 2 核 4G | 个人自用、数据量小 | 可行但偏紧，需限制 JVM 堆，见 7.2 |
| 4 核 8G | 推荐 | 6 个 Java 进程 + MySQL + Nacos 留有裕量 |
| 系统盘 40G+ | 数据 | 个股全市场日线约 300 万行，约 1~2G |

> 不要在 2 核 4G 上跑 Maven/npm 构建，容易 OOM。推荐**本地打包，上传产物**。

### 0.3 必须修改的 Windows 专属配置

以下配置写死在 `report-service/src/main/resources/application.yml`，Linux 上必须覆盖（第 7 节用启动参数覆盖，不改源码）：

```yaml
knowledge.hub-dir: C:/Users/22692/...        # 知识库 docs 目录
pipeline.python: C:/Users/.../python.exe     # Python 解释器
pipeline.collector-dir: D:/stock-analyzer/collector
pipeline.cron: 0 0 16 * * MON-FRI            # 见 10.3 关于 16:00 的说明
```

另外 5 个服务的 Nacos 地址是 `localhost:8848`，单机部署无需修改。

---

## 1. 云资源准备

1. 购买 ECS：地域选离自己近的（如华东1-杭州），镜像 `Ubuntu 22.04 64位`，系统盘 40G 以上。
2. 绑定弹性公网 IP（EIP）。
3. 安全组入方向规则：

| 协议 | 端口 | 授权对象 |
| --- | --- | --- |
| TCP | 80 | 0.0.0.0/0 |
| TCP | 443 | 0.0.0.0/0（上 HTTPS 后） |
| TCP | 22 | 你的公网 IP/32 |

> 不要放行 8080/8081-8084/3306/8848。服务之间走内网回环，Nginx 反向代理即可。
> 若要用域名访问，大陆地域 ECS 需先完成 ICP 备案，否则只能用 IP 访问。

---

## 2. 服务器初始化

```bash
# 以 root 或具备 sudo 的账号登录
ssh root@<公网IP>

# 时区（定时任务依赖，必须是上海时区）
timedatectl set-timezone Asia/Shanghai
timedatectl status

# 更新与基础工具
apt update && apt upgrade -y
apt install -y curl wget unzip git nginx mysql-server python3 python3-venv python3-pip

# 创建运行账号（不要用 root 跑服务）
useradd -m -s /bin/bash stock
mkdir -p /opt/stock-analyzer/{app,collector,sql,logs}
mkdir -p /var/www/stock-analyzer
chown -R stock:stock /opt/stock-analyzer
chown -R www-data:www-data /var/www/stock-analyzer

# 2G 交换分区（2C4G 机器建议加，8G 可跳过）
fallocate -l 2G /swapfile && chmod 600 /swapfile
mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

---

## 3. 安装 JDK 8、Nacos、Python 依赖

### 3.1 JDK 8

```bash
apt install -y openjdk-8-jdk
java -version   # 应显示 1.8.0_xxx
update-alternatives --set java /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java
```

> Ubuntu 24.04 默认源已无 openjdk-8，请改用 Temurin 8 的 tar.gz 解压到 `/usr/local/jdk8`，后续命令中的 `java` 换成 `/usr/local/jdk8/bin/java`。

### 3.2 Nacos（注册中心）

```bash
cd /opt
# 若服务器访问 GitHub 慢，先在本地下载后用 scp 上传
wget https://github.com/alibaba/nacos/releases/download/2.2.3/nacos-server-2.2.3.tar.gz
tar -xzf nacos-server-2.2.3.tar.gz
mv nacos nacos-server
chown -R stock:stock /opt/nacos-server

# 单机模式启动（默认 512m 堆，够用）
sudo -u stock bash /opt/nacos-server/bin/startup.sh -m standalone
sudo -u stock tail -f /opt/nacos-server/logs/start.out
# 看到 "Nacos started successfully" 后 Ctrl+C 退出
```

Nacos 控制台默认地址 `http://<内网IP>:8848/nacos`，账号密码同为 `nacos`。**不要暴露到公网**。

### 3.3 Python 采集环境

```bash
python3 -m venv /opt/stock-analyzer/venv
/opt/stock-analyzer/venv/bin/pip install -U pip -i https://mirrors.aliyun.com/pypi/simple
/opt/stock-analyzer/venv/bin/pip install -r /opt/stock-analyzer/collector/requirements.txt \
    -i https://mirrors.aliyun.com/pypi/simple
```

> 采集脚本用到了 `str | None` 和 `list[str]` 语法，要求 Python 3.10+，Ubuntu 22.04 自带的 3.10 满足。

---

## 4. 数据库初始化

### 4.1 建库建用户

```bash
sudo mysql <<'SQL'
CREATE DATABASE IF NOT EXISTS stock_analyzer DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'stock'@'127.0.0.1' IDENTIFIED BY '换成你的强密码';
GRANT ALL PRIVILEGES ON stock_analyzer.* TO 'stock'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
```

### 4.2 建表

```bash
mysql -ustock -p -h127.0.0.1 stock_analyzer < /opt/stock-analyzer/sql/schema_mysql.sql
mysql -ustock -p -h127.0.0.1 stock_analyzer -e "SHOW TABLES;"
```

应看到：`trade_cal`、`daily_quote`、`index_daily`、`collect_log`、`stock_basic`、`factor_config`、`backtest_result`、`watchlist`、`alert_rule`、`alert_event`、`portfolio_position`。

### 4.3 迁移本地已有数据（推荐，省去重新回填）

在本地 Windows 执行导出：

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqldump.exe" -uroot -proot123456 `
  --single-transaction --quick --set-gtid-purged=OFF stock_analyzer > stock_analyzer.sql
scp stock_analyzer.sql root@<公网IP>:/tmp/
```

服务器上导入：

```bash
mysql -ustock -p -h127.0.0.1 stock_analyzer < /tmp/stock_analyzer.sql
```

### 4.4 MySQL 小内存调优（可选）

编辑 `/etc/mysql/mysql.conf.d/mysqld.cnf`：

```ini
[mysqld]
bind-address = 127.0.0.1
innodb_buffer_pool_size = 512M
max_connections = 100
character-set-server = utf8mb4
```

```bash
systemctl restart mysql
systemctl enable mysql
```

---

## 5. 后端打包与上传

### 5.1 本地打包（推荐）

Windows PowerShell（JDK 1.8 + Maven 3.9.16）：

```powershell
$env:JAVA_HOME='C:\Users\22692\tools\jdk8u504-b01'
& 'C:\Users\22692\tools\apache-maven-3.9.16\bin\mvn.cmd' -DskipTests clean package
```

产物（每个约 30~50MB）：

```
quote-service/target/quote-service-0.1.0-SNAPSHOT.jar
analysis-service/target/analysis-service-0.1.0-SNAPSHOT.jar
report-service/target/report-service-0.1.0-SNAPSHOT.jar
portfolio-service/target/portfolio-service-0.1.0-SNAPSHOT.jar
gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar
```

上传并统一改名，便于 systemd 引用：

```powershell
scp quote-service\target\quote-service-0.1.0-SNAPSHOT.jar root@<IP>:/opt/stock-analyzer/app/quote-service.jar
scp analysis-service\target\analysis-service-0.1.0-SNAPSHOT.jar root@<IP>:/opt/stock-analyzer/app/analysis-service.jar
scp report-service\target\report-service-0.1.0-SNAPSHOT.jar root@<IP>:/opt/stock-analyzer/app/report-service.jar
scp portfolio-service\target\portfolio-service-0.1.0-SNAPSHOT.jar root@<IP>:/opt/stock-analyzer/app/portfolio-service.jar
scp gateway-service\target\gateway-service-0.1.0-SNAPSHOT.jar root@<IP>:/opt/stock-analyzer/app/gateway-service.jar

# 采集脚本与建表脚本
scp -r collector sql root@<IP>:/opt/stock-analyzer/
ssh root@<IP> "chown -R stock:stock /opt/stock-analyzer"
```

> 也可以 `git clone` 主仓库到服务器，但代码目录与运行目录混在一起，升级时容易误删数据，建议仍按上面的分离方式上传产物。

### 5.2 服务器上构建（可选）

```bash
apt install -y maven
cd /opt/stock-analyzer-src
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 mvn -DskipTests clean package
```

内存不足时加 `MAVEN_OPTS="-Xmx512m"`。

---

## 6. 环境变量文件

服务共享一份环境变量，避免把密码写进多个 unit：

```bash
sudo tee /opt/stock-analyzer/app.env >/dev/null <<'EOF'
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_USER=stock
MYSQL_PASSWORD=换成你的强密码
MYSQL_DB=stock_analyzer
EOF

sudo chown root:stock /opt/stock-analyzer/app.env
sudo chmod 640 /opt/stock-analyzer/app.env
```

> `report-service` 启动 Python 子进程时会继承这些变量，因此采集脚本也用同一个数据库账号，无需改 `collector/config.py`。
> 若想启用 Tushare，追加 `TUSHARE_TOKEN=xxxx`；不配也能跑（走 Baostock/腾讯兜底）。

---

## 7. systemd 托管

### 7.1 生成 unit 文件

以 root 执行：

```bash
mkunit() {
  name=$1; xmx=$2; extra=$3
  cat > /etc/systemd/system/${name}.service <<EOF
[Unit]
Description=stock-analyzer ${name}
After=network-online.target mysql.service
Wants=network-online.target

[Service]
User=stock
WorkingDirectory=/opt/stock-analyzer
EnvironmentFile=/opt/stock-analyzer/app.env
Environment="JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64"
ExecStart=/usr/bin/java -Xms128m -Xmx${xmx} -Dfile.encoding=UTF-8 -jar /opt/stock-analyzer/app/${name}.jar ${extra}
Restart=always
RestartSec=10
SuccessExitStatus=143
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
}

mkunit quote-service      256m ""
mkunit analysis-service   384m ""
mkunit report-service     384m "--pipeline.python=/opt/stock-analyzer/venv/bin/python --pipeline.collector-dir=/opt/stock-analyzer/collector --knowledge.hub-dir=/opt/finance-knowledge-hub/docs --pipeline.cron=0\ 0\ 18\ *\ *\ MON-FRI"
mkunit portfolio-service  256m ""
mkunit gateway-service    256m ""
```

> `report-service` 的四个参数必须按实际路径修改。cron 里空格在 systemd 中要用 `\ ` 转义；如果嫌麻烦，直接改 Jar 内 `application.yml` 再打包，或复制一份外部配置。

### 7.2 内存预算（2C4G 参考）

| 服务 | Xmx |
| --- | --- |
| gateway | 256m |
| quote | 256m |
| portfolio | 256m |
| analysis | 384m |
| report | 384m |
| Nacos | 512m（默认） |
| MySQL | 512m（buffer pool） |

合计约 2.5G，系统与页缓存留 1.5G。4G 机器建议加 2G swap。

### 7.3 Nacos 开机自启

```bash
cat > /etc/systemd/system/nacos.service <<'EOF'
[Unit]
Description=Nacos Server (standalone)
After=network.target

[Service]
User=stock
Environment="MODE=standalone"
ExecStart=/opt/nacos-server/bin/startup.sh -m standalone
ExecStop=/opt/nacos-server/bin/shutdown.sh
Restart=on-failure
RestartSec=15
TimeoutStartSec=120

[Install]
WantedBy=multi-user.target
EOF
```

> 注意：官方 `startup.sh` 是后台启动脚本，unit 里 `ExecStart` 会立即返回，systemd 认为启动完成。这是可接受的简化；更严谨的做法是用 `nacos-server.jar` 直接前台启动。

### 7.4 启动顺序

```bash
systemctl daemon-reload
systemctl enable --now mysql nacos
sleep 30 && systemctl status nacos --no-pager

systemctl enable --now quote-service analysis-service portfolio-service report-service
sleep 20
systemctl enable --now gateway-service

# 全部状态
systemctl status quote-service analysis-service report-service portfolio-service gateway-service --no-pager
```

> 服务注册到 Nacos 有 5~15 秒延迟，网关启动太快会短暂 503，等待即可。

---

## 8. 前端构建与 Nginx

### 8.1 本地构建

```powershell
cd C:\stock-analyzer\frontend
npm ci
npm run build      # 产物在 dist/
scp -r dist\* root@<IP>:/var/www/stock-analyzer/
```

> `package.json` 要求 Node `^22.18.0 || >=24.12.0`，本地版本过低时先升级。
> 前端所有请求走相对路径 `/api`，生产环境由 Nginx 反代，不需要改代码。

### 8.2 Nginx 站点配置

```bash
cat > /etc/nginx/sites-available/stock-analyzer <<'EOF'
server {
    listen 80;
    server_name _;

    root /var/www/stock-analyzer;
    index index.html;

    # 前端 history 路由回退
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 接口反向代理到网关（必须保留 /api 前缀，网关按 /api/xxx/** 路由）
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;   # 报告流水线/回测耗时较长
    }

    # 静态资源缓存
    location ~* \.(js|css|png|jpg|svg|woff2?)$ {
        expires 7d;
        add_header Cache-Control "public";
    }
}
EOF

ln -sf /etc/nginx/sites-available/stock-analyzer /etc/nginx/sites-enabled/stock-analyzer
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx
```

> `proxy_pass` 末尾**不要**加 `/`，否则 `/api` 前缀会被截掉，网关匹配不到路由。

---

## 9. 首次验证

```bash
# 1. 服务注册情况
curl -s http://127.0.0.1:8848/nacos/v1/ns/catalog/services?pageNo=1&pageSize=20 | head -c 500

# 2. 网关到各服务的链路
curl -s http://127.0.0.1:8080/api/index/list
curl -s http://127.0.0.1:8080/api/index/coverage
curl -s "http://127.0.0.1:8080/api/stock/list?page=1&size=5&sort=pct&order=desc"

# 3. 页面
curl -I http://<公网IP>/
```

浏览器打开 `http://<公网IP>`，应看到 10 个页签：市场概览、指数对比、个股行情、理财学习、因子打分、策略回测、自选预警、持仓管理、报告、数据覆盖。

### 9.1 采集首跑

```bash
cd /opt/stock-analyzer/collector
set -a; . /opt/stock-analyzer/app.env; set +a
/opt/stock-analyzer/venv/bin/python run_daily.py           # 指数增量
/opt/stock-analyzer/venv/bin/python run_stock.py --list    # 股票列表
/opt/stock-analyzer/venv/bin/python run_stock.py --all --limit 5   # 小批量试跑
```

查看入库结果：

```bash
mysql -ustock -p -h127.0.0.1 stock_analyzer -e \
 "SELECT COUNT(*) AS rows_cnt, COUNT(DISTINCT ts_code) AS stocks, MAX(trade_date) FROM daily_quote;"
```

### 9.2 手动触发完整流水线

```bash
curl -s -X POST http://127.0.0.1:8080/api/report/pipeline/run
```

返回 `ok:true` 且 `steps` 包含「指数采集 / 个股采集 / 报告生成 / 已推送」，即链路打通。

---

## 10. 采集与定时任务

### 10.1 知识库目录

```bash
git clone git@github.com:Achangan/finance-knowledge-hub.git /opt/finance-knowledge-hub
chown -R stock:stock /opt/finance-knowledge-hub
```

`report-service` 只负责把报告写到 `/opt/finance-knowledge-hub/docs/daily/YYYY-MM-DD.md`，**不会自动提交**。若要自动推送 GitHub，另配部署密钥并加一条 cron（示例）：

```bash
# /etc/cron.d/hub-push
0 19 * * 1-5 stock cd /opt/finance-knowledge-hub && git add docs/daily && git commit -m "daily: auto report" && git push
```

> 重要提醒：流水线用覆盖写的方式生成当天文件，**同一天手工追加的开发日志会被覆盖**。想保留人工内容，请把开发日志写到独立文件（如 `YYYY-MM-DD-dev.md`），或改为追加模式。

### 10.2 关于 16:00 / 18:00

Baostock、腾讯等免费通道的**当日日线一般在 18:00 之后**才更新。若 cron 保持 `0 0 16 * * MON-FRI`，16:00 采到的是**上一交易日**数据，报告里「最新交易日」会滞后一天。

建议改为 `0 0 18 * * MON-FRI`（本手册第 7.1 节已按 18:00 配置）。改完执行：

```bash
systemctl daemon-reload && systemctl restart report-service
```

### 10.3 全市场个股回填

```bash
cd /opt/stock-analyzer/collector
set -a; . /opt/stock-analyzer/app.env; set +a
nohup /opt/stock-analyzer/venv/bin/python run_stock.py --all --backfill \
  > /opt/stock-analyzer/logs/backfill.log 2>&1 &
```

- 约 5000 只、单只 0.1~0.8 秒，耗时数小时
- 幂等，可重复执行；中断后直接重跑（增量模式会续上）
- 长时间运行 Baostock 会掉登录态，脚本已内置重登重试
- 建议在夜间执行，避免和 18:00 定时任务抢 Baostock

---

## 11. 运维

### 11.1 日志

```bash
journalctl -u report-service -f              # 实时
journalctl -u gateway-service --since today  # 当天
journalctl -u quote-service -n 200 --no-pager
```

限制 journald 占用（避免撑满系统盘），编辑 `/etc/systemd/journald.conf`：

```ini
SystemMaxUse=1G
```

### 11.2 数据库备份

```bash
# 凭据放独立文件，避免密码出现在 cron 与进程列表里
cat > /root/.my.cnf <<'EOF'
[client]
host=127.0.0.1
user=stock
password=换成你的强密码
EOF
chmod 600 /root/.my.cnf

mkdir -p /opt/backup
cat > /etc/cron.d/mysql-backup <<'EOF'
0 2 * * * root mysqldump --defaults-extra-file=/root/.my.cnf --single-transaction --quick stock_analyzer | gzip > /opt/backup/stock_$(date +\%F).sql.gz
EOF
```

再加一条保留 7 天的清理任务：

```bash
find /opt/backup -name "stock_*.sql.gz" -mtime +7 -delete
```

### 11.3 升级步骤

```bash
# 本地重新打包并上传同名 jar
systemctl stop gateway-service
systemctl restart quote-service analysis-service report-service portfolio-service
sleep 15
systemctl start gateway-service
curl -s http://127.0.0.1:8080/api/index/list >/dev/null && echo OK
```

前端升级：覆盖 `/var/www/stock-analyzer/` 内容后 `systemctl reload nginx`（无需重启）。

### 11.4 安全加固清单

- [ ] 安全组只放行 80/443/22（22 限制来源 IP）
- [ ] MySQL `bind-address=127.0.0.1`，不对公网监听
- [ ] Nacos 控制台不暴露公网；生产环境应关闭匿名注册或改默认密码
- [ ] 服务以 `stock` 普通用户运行，jar/env 权限 `640`
- [ ] `app.env` 含数据库密码，权限 `640 root:stock`
- [ ] 定期 `apt update && apt upgrade`
- [ ] 有条件时上 HTTPS：`apt install certbot python3-certbot-nginx && certbot --nginx -d your.domain`

---

## 12. 常见问题排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 页面能打开，接口 502 | 网关未启动 / Nacos 未注册 | `systemctl status gateway-service`、`journalctl -u gateway-service -n 100` |
| 网关 503 Service Unavailable | 业务服务未注册到 Nacos 或启动较慢 | 查 `http://127.0.0.1:8848/nacos` 服务列表，等待 15 秒再试 |
| 服务启动即退出，日志 `Could not resolve placeholder` | 启动参数漏了 `knowledge.hub-dir` 等 | 核对第 7.1 节 report-service 的 `extra` 参数 |
| 启动报 `Access denied for user 'stock'@'localhost'` | 账号建成了 `'stock'@'127.0.0.1'`，而连接串解析成 localhost | 连接串已固定走 `MYSQL_HOST=127.0.0.1`，确认 env 生效：`systemctl show quote-service -p Environment` |
| 报告写成空/写不进去 | `/opt/finance-knowledge-hub` 属主不是 stock | `chown -R stock:stock /opt/finance-knowledge-hub` |
| 定时任务没执行 | 容器/服务器时区不是上海 | `timedatectl set-timezone Asia/Shanghai && systemctl restart report-service` |
| 采集报「用户未登录」 | Baostock 长连接掉线 | 脚本已自动重登；仍失败则缩短批次或稍后重跑 |
| 采集超时 | 全市场回填超过 15 分钟 | 手动跑回填，不要放在流水线里 |
| 页面刷新 404 | Nginx 缺 history 回退 | 检查 `try_files $uri $uri/ /index.html` |
| 接口 404，路径少了一段 | `proxy_pass` 多了结尾 `/` 或网关 StripPrefix 不匹配 | 保持 `proxy_pass http://127.0.0.1:8080;` |
| 内存告警、进程被 OOM Kill | JVM 堆总和超过物理内存 | 按 7.2 下调 Xmx，或升配到 4C8G |

---

## 13. 部署检查清单

- [ ] 安全组只开放 80/443/22
- [ ] 时区为 `Asia/Shanghai`
- [ ] MySQL 已建库建表，本地数据已导入
- [ ] Nacos standalone 正常，5 个服务均已注册
- [ ] `/api/index/list` 返回 200
- [ ] 前端页面 10 个页签可正常切换，个股排序可用
- [ ] `app.env` 权限 640，密码非默认
- [ ] `pipeline.cron` 按需设为 16:00 或 18:00
- [ ] `knowledge.hub-dir` 指向服务器实际路径，属主为 stock
- [ ] 数据库每日备份任务已配置
- [ ] 全市场个股回填已启动或已导入历史数据

---

> 说明：本手册中的 IP、域名、密码均为占位符，请替换为实际值。部署过程涉及公网暴露，请务必按第 11.4 节加固。
