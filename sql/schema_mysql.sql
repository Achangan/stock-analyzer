-- stock-analyzer P1 数据库结构（MySQL 8.0）
-- 设计原则：原始数据原样落库，业务计算在上层做；主键防重，可反复回填

-- 交易日历
CREATE TABLE IF NOT EXISTS trade_cal (
    cal_date    DATE PRIMARY KEY,           -- YYYY-MM-DD
    is_open     TINYINT NOT NULL            -- 1=交易日 0=休市
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 个股日线行情
CREATE TABLE IF NOT EXISTS daily_quote (
    ts_code     VARCHAR(20) NOT NULL,       -- 600519.SH
    trade_date  DATE NOT NULL,              -- YYYY-MM-DD
    open        DECIMAL(12,4),
    high        DECIMAL(12,4),
    low         DECIMAL(12,4),
    close       DECIMAL(12,4),
    pre_close   DECIMAL(12,4),
    `change`    DECIMAL(12,4),
    pct_chg     DECIMAL(10,4),
    vol         DECIMAL(20,2),              -- 成交量（股，Baostock/腾讯口径）
    amount      DECIMAL(20,4),              -- 成交额（元）
    PRIMARY KEY (ts_code, trade_date),
    INDEX idx_daily_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 指数日线行情（沪深300/中证500/创业板指等）
CREATE TABLE IF NOT EXISTS index_daily (
    ts_code     VARCHAR(20) NOT NULL,       -- 000300.SH
    trade_date  DATE NOT NULL,
    open        DECIMAL(12,4),
    high        DECIMAL(12,4),
    low         DECIMAL(12,4),
    close       DECIMAL(12,4),
    pre_close   DECIMAL(12,4),
    pct_chg     DECIMAL(10,4),
    vol         DECIMAL(20,2),
    amount      DECIMAL(20,4),
    PRIMARY KEY (ts_code, trade_date),
    INDEX idx_index_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 采集任务日志（每次采集记录，便于排查）
CREATE TABLE IF NOT EXISTS collect_log (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_at      DATETIME NOT NULL,          -- ISO 时间
    source      VARCHAR(20) NOT NULL,       -- tushare / baostock
    target      VARCHAR(100) NOT NULL,      -- 表名或范围
    `rows`      INT,
    status      VARCHAR(20) NOT NULL,       -- success / failed
    message     TEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 个股基础信息（股票列表 + 所属行业，供个股采集与前端展示）
CREATE TABLE IF NOT EXISTS stock_basic (
    ts_code     VARCHAR(20) PRIMARY KEY,    -- 600519.SH
    name        VARCHAR(50),                -- 贵州茅台
    industry    VARCHAR(50),
    list_date   DATE,
    delist_date DATE,
    status      TINYINT NOT NULL DEFAULT 1, -- 1=上市 0=退市
    updated_at  DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== 以下为 P2~P5 新增模块表 =====

-- 因子权重配置（weights 存 JSON 串，如 {"mom20":0.4,"trend":0.3,"vol":0.2,"bias":0.1}）
CREATE TABLE IF NOT EXISTS factor_config (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(50) NOT NULL,
    weights     TEXT NOT NULL,
    is_default  TINYINT NOT NULL DEFAULT 0,
    updated_at  DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 回测结果（nav_json 存净值曲线点集）
CREATE TABLE IF NOT EXISTS backtest_result (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    ts_code      VARCHAR(20) NOT NULL,
    strategy     VARCHAR(30) NOT NULL,      -- ma_cross 等
    params       TEXT,                       -- JSON：{"fast":5,"slow":20}
    start_date   DATE,
    end_date     DATE,
    total_return DECIMAL(12,4),              -- %
    max_drawdown DECIMAL(12,4),              -- %
    win_rate     DECIMAL(10,4),              -- %
    nav_json     MEDIUMTEXT,
    created_at   DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 自选列表
CREATE TABLE IF NOT EXISTS watchlist (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    ts_code     VARCHAR(20) NOT NULL,
    note        VARCHAR(200),
    sort        INT NOT NULL DEFAULT 0,
    created_at  DATETIME NOT NULL,
    UNIQUE KEY uk_watch_code (ts_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 预警规则
CREATE TABLE IF NOT EXISTS alert_rule (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    ts_code     VARCHAR(20) NOT NULL,       -- * 表示对全部自选生效
    rule_type   VARCHAR(30) NOT NULL,       -- ma20_break / bias20 / pct_chg
    threshold   DECIMAL(10,4),
    enabled     TINYINT NOT NULL DEFAULT 1,
    created_at  DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 预警事件
CREATE TABLE IF NOT EXISTS alert_event (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_id     BIGINT,
    ts_code     VARCHAR(20) NOT NULL,
    trade_date  DATE,
    message     VARCHAR(255),
    read_flag   TINYINT NOT NULL DEFAULT 0,
    created_at  DATETIME NOT NULL,
    INDEX idx_event_code_date (ts_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 持仓（MySQL 8 保留字冲突，表名用 portfolio_position）
CREATE TABLE IF NOT EXISTS portfolio_position (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    ts_code     VARCHAR(20) NOT NULL,
    name        VARCHAR(50),
    cost_price  DECIMAL(12,4) NOT NULL,
    shares      DECIMAL(16,4) NOT NULL,
    buy_date    DATE,
    note        VARCHAR(200)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
