package com.bejo.service;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Google Cloud Storage implementation of {@link AdsAudioStorage}. The bucket is created and
 * locked down (uniform access + public-access-prevention) by {@code scripts/init-gcp.sh}, so
 * objects are reachable only through the short-lived V4 signed URLs this class mints.
 *
 * <p><b>Identity:</b> everything runs as the app's Application Default Credentials — on Cloud
 * Run, the {@code bejo-cloudrun} service account, which has {@code objectAdmin} on the bucket
 * (upload + the signed GET) and {@code serviceAccountTokenCreator} on itself (so it can V4-sign
 * without a downloaded key, via the IAM Credentials {@code signBlob} API). Both credential
 * objects are resolved lazily so a machine without ADC (local dev that never touches ads) still
 * starts.
 *
 * <p>Not active under the {@code test} profile — the functional tests bind
 * {@code InMemoryAdsAudioStorage} instead, so they need no bucket or credentials.
 */
@Service
@Profile("!test")
public class GcsAdsAudioStorage implements AdsAudioStorage {

    private static final Logger log = LoggerFactory.getLogger(GcsAdsAudioStorage.class);

    @Value("${app.ads.gcs-bucket:}")
    private String bucket;

    private volatile Storage storage;
    private volatile GoogleCredentials signingCredentials;

    @Override
    public void put(String objectName, byte[] content, String contentType) {
        requireBucket();
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
                .setContentType(contentType)
                .build();
        storage().create(blobInfo, content);
        log.info("[ADS] Uploaded audio gs://{}/{} ({} bytes)", bucket, objectName, content.length);
    }

    @Override
    public String signedDownloadUrl(String objectName, Duration ttl) {
        requireBucket();
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectName)).build();
        URL url = storage().signUrl(blobInfo, ttl.toSeconds(), TimeUnit.SECONDS,
                Storage.SignUrlOption.withV4Signature(),
                Storage.SignUrlOption.signWith((ServiceAccountSigner) signingCredentials()));
        return url.toString();
    }

    private void requireBucket() {
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("Penyimpanan iklan belum dikonfigurasi");
        }
    }

    private Storage storage() {
        Storage s = storage;
        if (s == null) {
            synchronized (this) {
                if (storage == null) {
                    storage = StorageOptions.getDefaultInstance().getService();
                }
                s = storage;
            }
        }
        return s;
    }

    // The credentials used to sign URLs. Must be a ServiceAccountSigner: a key-bearing service
    // account (signs locally) or Cloud Run's ComputeEngineCredentials (signs via IAM signBlob).
    private GoogleCredentials signingCredentials() {
        GoogleCredentials c = signingCredentials;
        if (c == null) {
            synchronized (this) {
                if (signingCredentials == null) {
                    try {
                        GoogleCredentials resolved = GoogleCredentials.getApplicationDefault();
                        if (!(resolved instanceof ServiceAccountSigner)) {
                            throw new IllegalStateException(
                                    "Kredensial default tidak bisa menandatangani URL (bukan service account)");
                        }
                        signingCredentials = resolved;
                    } catch (IOException e) {
                        throw new IllegalStateException("Gagal memuat kredensial penyimpanan iklan", e);
                    }
                }
                c = signingCredentials;
            }
        }
        return c;
    }
}
