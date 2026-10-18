package com.bejo.voice.service;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Cloud Storage implementation of {@link VoiceModelStorage}. The bucket is created and locked down (uniform access +
 * public-access-prevention) by {@code scripts/init-gcp.sh}, so objects are reachable only through the short-lived V4 signed URLs
 * minted here.
 *
 * <p><b>Identity:</b> Application Default Credentials — on Cloud Run the {@code bejo-cloudrun} service account, which has
 * {@code objectViewer} on the bucket and {@code serviceAccountTokenCreator} on itself, so it can V4-sign without a downloaded key
 * (IAM Credentials {@code signBlob}). Credentials are resolved lazily so a machine without ADC still starts.
 *
 * <p>Not active under the {@code test} profile — the functional tests bind {@code InMemoryVoiceModelStorage}.
 *
 * <p><b>Not exercised by any test:</b> CI has no bucket and no signing identity, so this class is verified only by the first real
 * run against the deployed bucket. What can still go wrong there is the environment, not the logic: the service account missing
 * {@code objectViewer} (reads fail) or {@code serviceAccountTokenCreator} on itself / the IAM Credentials API being off (signing
 * fails), or ADC not resolving to a {@link ServiceAccountSigner}. {@code VoiceModelsService} maps all of these to a 503.
 */
@Service
@Profile("!test")
public class GcsVoiceModelStorage implements VoiceModelStorage {

    @Value("${app.voice.models.gcs-bucket:}")
    private String bucket;

    private volatile Storage storage;
    private volatile GoogleCredentials signingCredentials;

    @Override
    public Optional<byte[]> read(String objectName) {
        requireBucket();
        Blob blob = storage().get(BlobId.of(bucket, objectName));
        return blob == null ? Optional.empty() : Optional.of(blob.getContent());
    }

    @Override
    public String signedDownloadUrl(String objectName, Duration ttl) {
        requireBucket();
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName)).build();
        return storage().signUrl(info, ttl.toSeconds(), TimeUnit.SECONDS,
                Storage.SignUrlOption.withV4Signature(),
                Storage.SignUrlOption.signWith((ServiceAccountSigner) signingCredentials())).toString();
    }

    private void requireBucket() {
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("Penyimpanan model suara belum dikonfigurasi");
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

    // Must be a ServiceAccountSigner: a key-bearing service account (signs locally) or Cloud Run's ComputeEngineCredentials (signs via signBlob).
    private GoogleCredentials signingCredentials() {
        GoogleCredentials c = signingCredentials;
        if (c == null) {
            synchronized (this) {
                if (signingCredentials == null) {
                    try {
                        GoogleCredentials resolved = GoogleCredentials.getApplicationDefault();
                        if (!(resolved instanceof ServiceAccountSigner)) {
                            throw new IllegalStateException("Kredensial default tidak bisa menandatangani URL (bukan service account)");
                        }
                        signingCredentials = resolved;
                    } catch (IOException e) {
                        throw new IllegalStateException("Gagal memuat kredensial penyimpanan model suara", e);
                    }
                }
                c = signingCredentials;
            }
        }
        return c;
    }
}
