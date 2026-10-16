package org.taniwha.kerberos;

import com.mongodb.ConnectionString;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.apache.kerby.kerberos.kdc.identitybackend.JsonIdentityBackend;
import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.identity.backend.AbstractIdentityBackend;
import org.apache.kerby.kerberos.kerb.identity.backend.BackendConfig;
import org.apache.kerby.kerberos.kerb.request.KrbIdentity;
import org.apache.kerby.kerberos.kerb.type.KerberosTime;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionKey;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionType;
import org.bson.Document;
import org.bson.types.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Projections.include;

/**
 * Apache Kerby identity backend that persists complete KDC identities in MongoDB.
 */
public class MongoIdentityBackend extends AbstractIdentityBackend {

    public static final String MONGODB_URI = "mongo.identity.uri";
    public static final String MONGODB_DATABASE = "mongo.identity.database";
    public static final String MONGODB_COLLECTION = "mongo.identity.collection";
    public static final String MIGRATE_JSON = "mongo.identity.migrate-json";
    public static final String JSON_BACKEND_DIR = "mongo.identity.json-backend-dir";

    static final String DEFAULT_DATABASE = "mediata";
    static final String DEFAULT_COLLECTION = "kerberos_identities";

    private static final String MIGRATION_ID = "json-identity-backend-v1";
    private static final Logger logger = LoggerFactory.getLogger(MongoIdentityBackend.class);

    private MongoClient mongoClient;
    private MongoCollection<Document> identities;
    private MongoCollection<Document> migrations;

    @Override
    protected void doInitialize() throws KrbException {
        BackendConfig config = getBackendConfig();
        String uri = config.getString(MONGODB_URI);
        if (uri == null || uri.isBlank()) {
            throw new KrbException("MongoDB URI is required for the Kerberos identity backend");
        }

        try {
            ConnectionString connectionString = new ConnectionString(uri);
            String configuredDatabase = config.getString(MONGODB_DATABASE);
            String databaseName = firstNonBlank(
                    configuredDatabase,
                    connectionString.getDatabase(),
                    DEFAULT_DATABASE
            );
            String collectionName = firstNonBlank(
                    config.getString(MONGODB_COLLECTION),
                    DEFAULT_COLLECTION
            );

            mongoClient = MongoClients.create(connectionString);
            MongoDatabase database = mongoClient.getDatabase(databaseName);
            database.runCommand(new Document("ping", 1));
            identities = database.getCollection(collectionName);
            migrations = database.getCollection(collectionName + "_migrations");

            if (Boolean.TRUE.equals(config.getBoolean(MIGRATE_JSON, true))) {
                migrateJsonIdentities(config.getString(JSON_BACKEND_DIR));
            }
            logger.info("Kerberos identities are backed by MongoDB collection {}.{}",
                    databaseName, collectionName);
        } catch (KrbException e) {
            closeClient();
            throw e;
        } catch (RuntimeException e) {
            closeClient();
            throw new KrbException("Unable to initialize the MongoDB Kerberos identity backend", e);
        }
    }

    @Override
    protected Iterable<String> doGetIdentities() throws KrbException {
        try {
            List<String> principalNames = new ArrayList<>();
            identities.find().projection(include("_id")).sort(new Document("_id", 1))
                    .map(document -> document.getString("_id"))
                    .into(principalNames);
            return principalNames;
        } catch (MongoException e) {
            throw databaseFailure("list identities", e);
        }
    }

    @Override
    protected KrbIdentity doGetIdentity(String principalName) throws KrbException {
        try {
            Document document = identities.find(eq("_id", principalName)).first();
            return document == null ? null : fromDocument(document);
        } catch (MongoException | IllegalArgumentException e) {
            throw databaseFailure("read identity " + principalName, e);
        }
    }

    @Override
    protected KrbIdentity doAddIdentity(KrbIdentity identity) throws KrbException {
        try {
            identities.insertOne(toDocument(identity));
            return identity;
        } catch (MongoException | IllegalArgumentException e) {
            throw databaseFailure("add identity " + identity.getPrincipalName(), e);
        }
    }

    @Override
    protected KrbIdentity doUpdateIdentity(KrbIdentity identity) throws KrbException {
        try {
            identities.replaceOne(eq("_id", identity.getPrincipalName()), toDocument(identity));
            return identity;
        } catch (MongoException | IllegalArgumentException e) {
            throw databaseFailure("update identity " + identity.getPrincipalName(), e);
        }
    }

    @Override
    protected void doDeleteIdentity(String principalName) throws KrbException {
        try {
            identities.deleteOne(eq("_id", principalName));
        } catch (MongoException e) {
            throw databaseFailure("delete identity " + principalName, e);
        }
    }

    @Override
    protected void doStop() {
        closeClient();
    }

    @Override
    protected void doRelease() {
        closeClient();
    }

    private void migrateJsonIdentities(String jsonBackendDir) throws KrbException {
        if (jsonBackendDir == null || jsonBackendDir.isBlank()
                || migrations.find(eq("_id", MIGRATION_ID)).first() != null) {
            return;
        }

        File sourceDirectory = new File(jsonBackendDir);
        if (!sourceDirectory.isDirectory()) {
            logger.info("No legacy Kerberos JSON directory found at {}; skipping migration",
                    sourceDirectory.getAbsolutePath());
            return;
        }

        BackendConfig jsonConfig = new BackendConfig();
        jsonConfig.setString(JsonIdentityBackend.JSON_IDENTITY_BACKEND_DIR,
                sourceDirectory.getAbsolutePath());
        JsonIdentityBackend jsonBackend = new JsonIdentityBackend(jsonConfig);
        int imported = 0;
        try {
            jsonBackend.initialize();
            for (String principalName : jsonBackend.getIdentities()) {
                if (identities.find(eq("_id", principalName)).first() == null) {
                    KrbIdentity identity = jsonBackend.getIdentity(principalName);
                    if (identity != null) {
                        identities.insertOne(toDocument(identity));
                        imported++;
                    }
                }
            }
            migrations.insertOne(new Document("_id", MIGRATION_ID)
                    .append("source", sourceDirectory.getAbsolutePath())
                    .append("imported", imported)
                    .append("completedAt", new Date()));
            logger.info("Migrated {} Kerberos identities from JSON to MongoDB", imported);
        } catch (RuntimeException e) {
            throw new KrbException("Failed to migrate Kerberos identities from JSON", e);
        } finally {
            try {
                jsonBackend.stop();
            } catch (KrbException e) {
                logger.warn("Failed to stop the legacy JSON identity backend after migration", e);
            }
            jsonBackend.release();
        }
    }

    static Document toDocument(KrbIdentity identity) {
        List<Document> keys = identity.getKeys().values().stream()
                .sorted((left, right) -> Integer.compare(
                        left.getKeyType().getValue(), right.getKeyType().getValue()))
                .map(key -> new Document("type", key.getKeyType().getValue())
                        .append("kvno", key.getKvno())
                        .append("data", new Binary(key.getKeyData())))
                .toList();

        return new Document("_id", identity.getPrincipalName())
                .append("keyVersion", identity.getKeyVersion())
                .append("kdcFlags", identity.getKdcFlags())
                .append("disabled", identity.isDisabled())
                .append("locked", identity.isLocked())
                .append("expireTime", timeValue(identity.getExpireTime()))
                .append("createdTime", timeValue(identity.getCreatedTime()))
                .append("keys", keys);
    }

    static KrbIdentity fromDocument(Document document) {
        KrbIdentity identity = new KrbIdentity(document.getString("_id"));
        identity.setKeyVersion(document.getInteger("keyVersion", 0));
        identity.setKdcFlags(document.getInteger("kdcFlags", 0));
        identity.setDisabled(Boolean.TRUE.equals(document.getBoolean("disabled")));
        identity.setLocked(Boolean.TRUE.equals(document.getBoolean("locked")));
        identity.setExpireTime(toKerberosTime(document.getLong("expireTime")));
        identity.setCreatedTime(toKerberosTime(document.getLong("createdTime")));

        List<Document> keys = document.getList("keys", Document.class, List.of());
        for (Document storedKey : keys) {
            EncryptionType type = EncryptionType.fromValue(storedKey.getInteger("type"));
            if (type == null) {
                throw new IllegalArgumentException("Unknown Kerberos encryption type");
            }
            Binary data = storedKey.get("data", Binary.class);
            identity.addKey(new EncryptionKey(type, data.getData(),
                    storedKey.getInteger("kvno", 0)));
        }
        return identity;
    }

    private static Long timeValue(KerberosTime time) {
        return time == null ? null : time.getTime();
    }

    private static KerberosTime toKerberosTime(Long time) {
        return time == null ? null : new KerberosTime(time);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static KrbException databaseFailure(String operation, RuntimeException cause) {
        return new KrbException("Failed to " + operation + " in MongoDB", cause);
    }

    private void closeClient() {
        if (mongoClient != null) {
            mongoClient.close();
            mongoClient = null;
        }
    }
}
