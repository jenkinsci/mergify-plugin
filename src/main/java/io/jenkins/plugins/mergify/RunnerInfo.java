package io.jenkins.plugins.mergify;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;

public final class RunnerInfo {
    public static final String DEFAULT_GROUP_NAME = "Default";
    public static final String BUILT_IN_NODE_NAME = "built-in";

    private final Integer id;
    private final String name;
    private final List<String> labels;

    public RunnerInfo(Integer id, String name, List<String> labels) {
        this.id = id;
        this.name = (name == null || name.isEmpty()) ? BUILT_IN_NODE_NAME : name;
        this.labels = labels == null ? List.of() : List.copyOf(labels);
    }

    public Integer getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<String> getLabels() {
        return labels;
    }

    public static RunnerInfo fromExecutor(Executor executor) {
        if (executor == null) {
            return null;
        }
        Computer computer = executor.getOwner();
        Node node = computer.getNode();
        return new RunnerInfo(executor.getNumber(), computer.getName(), extractLabels(node));
    }

    public static RunnerInfo fromNodeName(String nodeName) {
        return new RunnerInfo(null, nodeName, extractLabels(resolveNode(nodeName)));
    }

    private static Node resolveNode(String nodeName) {
        if (nodeName == null || nodeName.isEmpty() || BUILT_IN_NODE_NAME.equals(nodeName)) {
            return Jenkins.get();
        }
        return Jenkins.get().getNode(nodeName);
    }

    private static List<String> extractLabels(Node node) {
        if (node == null) {
            return List.of();
        }
        return node.getAssignedLabels().stream().map(LabelAtom::getName).collect(Collectors.toList());
    }
}
