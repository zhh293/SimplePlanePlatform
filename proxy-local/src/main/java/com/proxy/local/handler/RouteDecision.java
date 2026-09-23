package com.proxy.local.handler;

/**
 * 路由引擎返回的结构化决策。
 */
public final class RouteDecision {

    public enum Reason {
        SYSTEM, USER, PROVIDER, DEFAULT
    }

    private final RouteAction action;
    private final String ruleId;
    private final long ruleVersion;
    private final Reason reason;

    public RouteDecision(RouteAction action, String ruleId, long ruleVersion, Reason reason) {
        this.action = action;
        this.ruleId = ruleId == null ? "" : ruleId;
        this.ruleVersion = ruleVersion;
        this.reason = reason == null ? Reason.DEFAULT : reason;
    }

    public RouteAction getAction() { return action; }
    public String getRuleId() { return ruleId; }
    public long getRuleVersion() { return ruleVersion; }
    public Reason getReason() { return reason; }
    public boolean isDirect() { return action == RouteAction.DIRECT; }
    public boolean isProxy() { return action == RouteAction.PROXY; }
    public boolean isReject() { return action == RouteAction.REJECT; }

    @Override
    public String toString() {
        return "RouteDecision{" + action + ", ruleId='" + ruleId + '\'' +
                ", version=" + ruleVersion + ", reason=" + reason + '}';
    }
}
