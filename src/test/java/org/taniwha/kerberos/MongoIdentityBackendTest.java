package org.taniwha.kerberos;

import org.apache.kerby.kerberos.kdc.identitybackend.JsonIdentityBackend;
import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.identity.backend.BackendConfig;
import org.apache.kerby.kerberos.kerb.identity.backend.IdentityBackend;
import org.apache.kerby.kerberos.kerb.request.KrbIdentity;
import org.apache.kerby.kerberos.kerb.type.KerberosTime;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionKey;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MongoIdentityBackendTest {

    @Container
    private static final MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @TempDir
    Path tempDir;

    @Test
    void mongoBackendMatchesJsonCrudAndRestartPersistence() throws Exception {
        assertBackendContract(this::newJsonBackend);
        assertBackendContract(() -> newMongoBackend("contract_identities", false, null));
    }

    @Test
    void migratesLegacyJsonIdentitiesOnceWithoutOverwritingMongoChanges() throws Exception {
        KrbIdentity legacy = sampleIdentity("legacy@TEST.REALM");
        IdentityBackend json = newJsonBackend();
        json.addIdentity(legacy);
        json.stop();
        json.release();

        MongoIdentityBackend firstMongo = newMongoBackend(
                "migration_identities", true, tempDir.resolve("krb5kdc"));
        assertIdentityEquals(legacy, firstMongo.getIdentity(legacy.getPrincipalName()));

        KrbIdentity changedInMongo = firstMongo.getIdentity(legacy.getPrincipalName());
        changedInMongo.setLocked(true);
        changedInMongo.setKeyVersion(9);
        firstMongo.updateIdentity(changedInMongo);
        firstMongo.stop();

        MongoIdentityBackend restartedMongo = newMongoBackend(
                "migration_identities", true, tempDir.resolve("krb5kdc"));
        KrbIdentity persisted = restartedMongo.getIdentity(legacy.getPrincipalName());
        assertThat(persisted.isLocked()).isTrue();
        assertThat(persisted.getKeyVersion()).isEqualTo(9);
        restartedMongo.stop();
    }

    private void assertBackendContract(BackendFactory backendFactory) throws Exception {
        String principal = "contract-" + System.nanoTime() + "@TEST.REALM";
        KrbIdentity original = sampleIdentity(principal);

        IdentityBackend backend = backendFactory.create();
        backend.addIdentity(original);
        assertIdentityEquals(original, backend.getIdentity(principal));
        assertThat(StreamSupport.stream(backend.getIdentities().spliterator(), false))
                .contains(principal);

        KrbIdentity updated = backend.getIdentity(principal);
        updated.setDisabled(true);
        updated.setLocked(true);
        updated.setKeyVersion(4);
        backend.updateIdentity(updated);
        backend.stop();
        backend.release();

        IdentityBackend restarted = backendFactory.create();
        assertIdentityEquals(updated, restarted.getIdentity(principal));
        restarted.deleteIdentity(principal);
        assertThat(restarted.getIdentity(principal)).isNull();
        restarted.stop();
        restarted.release();
    }

    private JsonIdentityBackend newJsonBackend() throws KrbException {
        BackendConfig config = new BackendConfig();
        config.setString(JsonIdentityBackend.JSON_IDENTITY_BACKEND_DIR,
                tempDir.resolve("krb5kdc").toString());
        JsonIdentityBackend backend = new JsonIdentityBackend(config);
        backend.initialize();
        return backend;
    }

    private MongoIdentityBackend newMongoBackend(String collection, boolean migrate,
                                                   Path jsonDirectory) throws KrbException {
        BackendConfig config = new BackendConfig();
        config.setString(MongoIdentityBackend.MONGODB_URI, mongo.getReplicaSetUrl("kerberos_test"));
        config.setString(MongoIdentityBackend.MONGODB_COLLECTION, collection);
        config.setBoolean(MongoIdentityBackend.MIGRATE_JSON, migrate);
        if (jsonDirectory != null) {
            config.setString(MongoIdentityBackend.JSON_BACKEND_DIR, jsonDirectory.toString());
        }

        MongoIdentityBackend backend = new MongoIdentityBackend();
        backend.setConfig(config);
        backend.initialize();
        return backend;
    }

    private static KrbIdentity sampleIdentity(String principal) {
        KrbIdentity identity = new KrbIdentity(principal);
        identity.setKeyVersion(2);
        identity.setKdcFlags(17);
        identity.setDisabled(false);
        identity.setLocked(false);
        identity.setCreatedTime(new KerberosTime(1_700_000_000_000L));
        identity.setExpireTime(new KerberosTime(1_800_000_000_000L));
        identity.addKey(new EncryptionKey(
                EncryptionType.AES256_CTS_HMAC_SHA1_96,
                new byte[]{1, 2, 3, 4},
                2));
        identity.addKey(new EncryptionKey(
                EncryptionType.AES128_CTS_HMAC_SHA1_96,
                new byte[]{5, 6, 7, 8},
                2));
        return identity;
    }

    private static void assertIdentityEquals(KrbIdentity expected, KrbIdentity actual) {
        assertThat(actual).isNotNull();
        assertThat(actual.getPrincipalName()).isEqualTo(expected.getPrincipalName());
        assertThat(actual.getKeyVersion()).isEqualTo(expected.getKeyVersion());
        assertThat(actual.getKdcFlags()).isEqualTo(expected.getKdcFlags());
        assertThat(actual.isDisabled()).isEqualTo(expected.isDisabled());
        assertThat(actual.isLocked()).isEqualTo(expected.isLocked());
        assertThat(actual.getCreatedTime()).isEqualTo(expected.getCreatedTime());
        assertThat(actual.getExpireTime()).isEqualTo(expected.getExpireTime());
        assertThat(actual.getKeys()).containsOnlyKeys(expected.getKeys().keySet());
        expected.getKeys().forEach((type, expectedKey) -> {
            EncryptionKey actualKey = actual.getKey(type);
            assertThat(actualKey.getKvno()).isEqualTo(expectedKey.getKvno());
            assertThat(actualKey.getKeyData()).containsExactly(expectedKey.getKeyData());
        });
    }

    @FunctionalInterface
    private interface BackendFactory {
        IdentityBackend create() throws KrbException;
    }
}
