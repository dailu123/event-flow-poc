package com.hsbc.pluse.dispatch;

public class DispatcherConfig {

    private int orderedWorkerCount = 16;
    private int concurrentCoreSize = 8;
    private int concurrentMaxSize = 32;

    public int getOrderedWorkerCount() { return orderedWorkerCount; }
    public void setOrderedWorkerCount(int v) { this.orderedWorkerCount = v; }

    public int getConcurrentCoreSize() { return concurrentCoreSize; }
    public void setConcurrentCoreSize(int v) { this.concurrentCoreSize = v; }

    public int getConcurrentMaxSize() { return concurrentMaxSize; }
    public void setConcurrentMaxSize(int v) { this.concurrentMaxSize = v; }
}
