package com.stockanalyzer.model;

/** 指数日线（对应 index_daily 表一行） */
public class IndexDaily {
    private String tsCode;
    private String tradeDate;
    private double close;
    private double pctChg;

    public IndexDaily() {
    }

    public IndexDaily(String tsCode, String tradeDate, double close, double pctChg) {
        this.tsCode = tsCode;
        this.tradeDate = tradeDate;
        this.close = close;
        this.pctChg = pctChg;
    }

    public String getTsCode() {
        return tsCode;
    }

    public void setTsCode(String tsCode) {
        this.tsCode = tsCode;
    }

    public String getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(String tradeDate) {
        this.tradeDate = tradeDate;
    }

    public double getClose() {
        return close;
    }

    public void setClose(double close) {
        this.close = close;
    }

    public double getPctChg() {
        return pctChg;
    }

    public void setPctChg(double pctChg) {
        this.pctChg = pctChg;
    }
}
