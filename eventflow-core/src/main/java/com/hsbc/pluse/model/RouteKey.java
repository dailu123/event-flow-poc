package com.hsbc.pluse.model;

import java.util.Objects;

/**
 * Routing key computed from event attributes, used to match pipelines.
 * Supports wildcard matching: "t_order:*" matches any operation on t_order.
 */
public record RouteKey(String table, String operation) {

    public RouteKey {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
    }

    public boolean matches(RouteKey pattern) {
        return wildcardMatch(pattern.table, this.table)
                && wildcardMatch(pattern.operation, this.operation);
    }

    private static boolean wildcardMatch(String pattern, String value) {
        if ("*".equals(pattern)) return true;
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(value);
    }

    public static RouteKey parse(String expr) {
        int idx = expr.indexOf(':');
        if (idx < 0) {
            return new RouteKey(expr, "*");
        }
        return new RouteKey(expr.substring(0, idx), expr.substring(idx + 1));
    }

    @Override
    public String toString() {
        return table + ":" + operation;
    }
}
