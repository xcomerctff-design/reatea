package net.reagen.verify;

import java.util.ArrayList;
import java.util.List;

/** Отчёт проверки процедуры (аналог диагностических сообщений MCreator). */
public class VerifyReport {
    public enum Severity { ERROR, WARNING }

    public static class Issue {
        public final String elementId;
        public final Severity severity;
        public final String rule;
        public final String message;
        public Issue(String elementId, Severity sev, String rule, String message) {
            this.elementId = elementId; this.severity = sev; this.rule = rule; this.message = message;
        }
    }

    public final List<Issue> issues = new ArrayList<>();
    public boolean valid() { return issues.stream().noneMatch(i -> i.severity == Severity.ERROR); }

    public void error(String elementId, String rule, String msg) {
        issues.add(new Issue(elementId, Severity.ERROR, rule, msg));
    }
    public void warn(String elementId, String rule, String msg) {
        issues.add(new Issue(elementId, Severity.WARNING, rule, msg));
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"valid\":").append(valid()).append(",\"issues\":[");
        for (int i = 0; i < issues.size(); i++) {
            Issue is = issues.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"elementId\":\"").append(is.elementId)
              .append("\",\"severity\":\"").append(is.severity)
              .append("\",\"rule\":\"").append(is.rule)
              .append("\",\"message\":\"").append(esc(is.message)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
