package br.com.espacotucuma.sync.contract;

import java.util.List;

/** Persisted outcomes for operation IDs already received by the remote service. */
public record OperationsStatusResponse(int contractVersion, List<OperationResult> results) {}
