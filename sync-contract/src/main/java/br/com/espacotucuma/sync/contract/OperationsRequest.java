package br.com.espacotucuma.sync.contract;

import java.util.List;

public record OperationsRequest(int contractVersion, List<OperationSubmission> operations) {}
