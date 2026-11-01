package br.com.espacotucuma.sync;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** Private S3-compatible storage; public ACLs and permanent URLs are never created. */
@Component
class S3BackupObjectStorage implements BackupObjectStorage {
  private final String bucket;
  private final S3Client client;

  S3BackupObjectStorage(
      @Value("${tucuma.backup.storage.bucket:}") String bucket,
      @Value("${tucuma.backup.storage.region:us-east-1}") String region,
      @Value("${tucuma.backup.storage.endpoint:}") String endpoint,
      Environment environment) {
    this.bucket = bucket;
    var builder = S3Client.builder().region(Region.of(region));
    if (!endpoint.isBlank()) {
      URI uri = URI.create(endpoint);
      boolean demoOnlyProfile =
          environment.acceptsProfiles(Profiles.of("demo"))
              && !environment.acceptsProfiles(Profiles.of("prod", "production"));
      validateEndpoint(uri, demoOnlyProfile);
      builder.endpointOverride(uri).forcePathStyle(true);
    }
    this.client = builder.build();
  }

  @Override
  public void putPrivate(String key, byte[] content) {
    requireBucket();
    client.putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType("application/json")
            .contentLength((long) content.length)
            .serverSideEncryption(ServerSideEncryption.AES256)
            .build(),
        RequestBody.fromBytes(content));
  }

  @Override
  public byte[] get(String key) {
    requireBucket();
    try {
      ResponseBytes<GetObjectResponse> response =
          client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
      return response.asByteArray();
    } catch (S3Exception exception) {
      if (exception.statusCode() == 404) {
        throw new BackupObjectMissingException("Objeto privado de backup não encontrado.");
      }
      throw exception;
    }
  }

  @Override
  public void delete(String key) {
    requireBucket();
    client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }

  private void requireBucket() {
    if (bucket.isBlank()) {
      throw new IllegalStateException("O bucket privado de backup não está configurado.");
    }
  }

  static void validateEndpoint(URI uri, boolean demoOnlyProfile) {
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    boolean secureEndpoint = "https".equals(scheme);
    boolean privateDemoEndpoint =
        demoOnlyProfile
            && "http".equals(scheme)
            && "rustfs".equalsIgnoreCase(uri.getHost())
            && uri.getPort() == 9000;
    if (!secureEndpoint && !privateDemoEndpoint) {
      throw new IllegalArgumentException(
          "O endpoint do storage de backup deve usar HTTPS; HTTP só é permitido para rustfs:9000 no perfil demo isolado.");
    }
  }

  @PreDestroy
  void close() {
    client.close();
  }
}
