package br.com.espacotucuma.sync.contract;

import java.util.List;

public record OperationsResponse(
    int contractVersion, long checkpoint, List<OperationResult> results) {}
