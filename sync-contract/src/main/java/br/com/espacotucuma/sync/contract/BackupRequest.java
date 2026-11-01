package br.com.espacotucuma.sync.contract;

public record BackupRequest(int contractVersion, long checkpoint) {}
