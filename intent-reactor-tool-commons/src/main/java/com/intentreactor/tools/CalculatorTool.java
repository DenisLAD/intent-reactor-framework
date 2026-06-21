package com.intentreactor.tools;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CalculatorTool implements Tool {

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public String getDescription() {
        return "Evaluates a mathematical expression. Parameter 'expression' is a string like '2 + 2' or '(10 * 3) / 5'.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "expression", Map.of("type", "string", "description", "Mathematical expression to evaluate")
                ),
                "required", java.util.List.of("expression")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String expression = (String) input.getParameters().get("expression");
        if (expression == null || expression.isBlank()) {
            return ToolResult.error("Expression is required");
        }
        try {
            double result = evaluate(expression.trim());
            return ToolResult.ok(result);
        } catch (Exception e) {
            return ToolResult.error("Failed to evaluate expression: " + e.getMessage());
        }
    }

    @Override
    public boolean isRisky() {
        return false;
    }

    /**
     * Simple recursive descent parser for basic arithmetic.
     */
    private double evaluate(String expr) {
        return new Parser(expr).parse();
    }

    private static class Parser {
        private final String expr;
        private int pos = 0;

        Parser(String expr) {
            this.expr = expr.replaceAll("\\s+", "");
        }

        double parse() {
            double result = parseAddSub();
            if (pos < expr.length()) throw new IllegalArgumentException("Unexpected character at " + pos);
            return result;
        }

        private double parseAddSub() {
            double left = parseMulDiv();
            while (pos < expr.length() && (expr.charAt(pos) == '+' || expr.charAt(pos) == '-')) {
                char op = expr.charAt(pos++);
                double right = parseMulDiv();
                left = op == '+' ? left + right : left - right;
            }
            return left;
        }

        private double parseMulDiv() {
            double left = parseUnary();
            while (pos < expr.length() && (expr.charAt(pos) == '*' || expr.charAt(pos) == '/')) {
                char op = expr.charAt(pos++);
                double right = parseUnary();
                if (op == '/' && right == 0) throw new ArithmeticException("Division by zero");
                left = op == '*' ? left * right : left / right;
            }
            return left;
        }

        private double parseUnary() {
            if (pos < expr.length() && expr.charAt(pos) == '-') {
                pos++;
                return -parsePrimary();
            }
            if (pos < expr.length() && expr.charAt(pos) == '+') {
                pos++;
            }
            return parsePrimary();
        }

        private double parsePrimary() {
            if (pos < expr.length() && expr.charAt(pos) == '(') {
                pos++;
                double val = parseAddSub();
                if (pos >= expr.length() || expr.charAt(pos) != ')') throw new IllegalArgumentException("Missing )");
                pos++;
                return val;
            }
            int start = pos;
            while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) pos++;
            if (pos == start) throw new IllegalArgumentException("Expected number at " + pos);
            return Double.parseDouble(expr.substring(start, pos));
        }
    }
}
