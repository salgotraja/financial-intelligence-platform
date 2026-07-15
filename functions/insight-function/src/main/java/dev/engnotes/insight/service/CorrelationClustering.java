package dev.engnotes.insight.service;

import dev.engnotes.insight.model.CorrelationEdge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Threshold clustering via union-find: two tickers land in the same group if they are connected by
 * a chain of qualifying edges, even without a direct edge between them (transitivity). A ticker
 * touched by no qualifying edge has no group (a component of size 1 is not returned).
 */
final class CorrelationClustering {

    private CorrelationClustering() {}

    /** Connected components over {@code edges}, each sorted ascending, singletons excluded. */
    static List<List<String>> connectedComponents(List<CorrelationEdge> edges) {
        Map<String, String> parent = new HashMap<>();
        for (CorrelationEdge edge : edges) {
            parent.putIfAbsent(edge.tickerA(), edge.tickerA());
            parent.putIfAbsent(edge.tickerB(), edge.tickerB());
            union(parent, edge.tickerA(), edge.tickerB());
        }

        Map<String, List<String>> membersByRoot = new TreeMap<>();
        for (String ticker : parent.keySet()) {
            membersByRoot
                    .computeIfAbsent(find(parent, ticker), root -> new ArrayList<>())
                    .add(ticker);
        }

        return membersByRoot.values().stream()
                .filter(members -> members.size() >= 2)
                .map(members -> members.stream().sorted().toList())
                .toList();
    }

    private static String find(Map<String, String> parent, String ticker) {
        String root = ticker;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        while (!parent.get(ticker).equals(root)) {
            String next = parent.get(ticker);
            parent.put(ticker, root);
            ticker = next;
        }
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String rootA = find(parent, a);
        String rootB = find(parent, b);
        if (!rootA.equals(rootB)) {
            parent.put(rootA, rootB);
        }
    }
}
