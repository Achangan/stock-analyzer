package com.stockanalyzer.model;

/** 指数分析摘要：行情 + 均线 + 波动率 */
public class IndexSummary {
    private String tsCode;
    private String latestDate;
    private double latestClose;
    private double latestPctChg;
    private Double ma5;
    private Double ma20;
    private Double ma60;
    /** 近 20 个交易日累计涨跌幅（%） */
    private Double change20d;
    /** 近 20 个交易日年化波动率（%） */
    private Double volatility20d;
    /** 近 60 个交易日累计涨跌幅（%） */
    private Double change60d;

    public String getTsCode() {
        return tsCode;
    }

    public void setTsCode(String tsCode) {
        this.tsCode = tsCode;
    }

    public String getLatestDate() {
        return latestDate;
    }

    public void setLatestDate(String latestDate) {
        this.latestDate = latestDate;
    }

    public double getLatestClose() {
        return latestClose;
    }

    public void setLatestClose(double latestClose) {
        this.latestClose = latestClose;
    }

    public double getLatestPctChg() {
        return latestPctChg;
    }

    public void setLatestPctChg(double latestPctChg) {
        this.latestPctChg = latestPctChg;
    }

    public Double getMa5() {
        return ma5;
    }

    public void setMa5(Double ma5) {
        this.ma5 = ma5;
    }

    public Double getMa20() {
        return ma20;
    }

    public void setMa20(Double ma20) {
        this.ma20 = ma20;
    }

    public Double getMa60() {
        return ma60;
    }

    public void setMa60(Double ma60) {
        this.ma60 = ma60;
    }

    public Double getChange20d() {
        return change20d;
    }

    public void setChange20d(Double change20d) {
        this.change20d = change20d;
    }

    public Double getVolatility20d() {
        return volatility20d;
    }

    public void setVolatility20d(Double volatility20d) {
        this.volatility20d = volatility20d;
    }

    public Double getChange60d() {
        return change60d;
    }

    public void setChange60d(Double change60d) {
        this.change60d = change60d;
    }
}
