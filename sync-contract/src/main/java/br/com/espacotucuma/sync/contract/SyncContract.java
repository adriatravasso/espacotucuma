package br.com.espacotucuma.sync.contract;

/** Constants shared by the offline desktop client and the remote synchronization service. */
public final class SyncContract {
  public static final int VERSION = 1;
  public static final String VERSION_HEADER = "X-Tucuma-Contract-Version";
  public static final int MAX_OPERATIONS_PER_REQUEST = 100;

  private SyncContract() {}
}
