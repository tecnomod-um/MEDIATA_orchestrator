package org.taniwha.kerberos;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.admin.kadmin.local.LocalKadmin;
import org.apache.kerby.kerberos.kerb.admin.kadmin.local.LocalKadminImpl;
import org.apache.kerby.kerberos.kerb.identity.backend.BackendConfig;
import org.apache.kerby.kerberos.kerb.server.KdcConfig;
import org.apache.kerby.kerberos.kerb.server.KdcServer;
import org.apache.kerby.util.NetworkUtil;

import java.io.File;

// Custom implementation to fix the faulty SimpleKdcServer behavior on preexisting backend
public class CustomKdcServer extends KdcServer {
    private LocalKadmin kadmin;

    public CustomKdcServer(KdcConfig kdcConfig, BackendConfig backendConfig) throws KrbException {
        super(kdcConfig, backendConfig);
        this.setKdcPort(NetworkUtil.getServerPort());
        this.setKdcRealm("TANIWHA.COM");
        this.setKdcHost("localhost");
    }

    @Override
    public synchronized void init() throws KrbException {
        super.init();
        this.kadmin = new LocalKadminImpl(this.getKdcSetting(), this.getIdentityService());
        // The library just throws an error if no principals are set in the check
        try {
            this.kadmin.checkBuiltinPrincipals();
        } catch (KrbException e) {
            // If they don't exist yet, create them
            this.kadmin.createBuiltinPrincipals();
        }
    }

    public synchronized void createPrincipal(String principal, String password) throws KrbException {
        if (this.kadmin.getPrincipal(principal) == null)
            this.kadmin.addPrincipal(principal, password);
    }

    public synchronized void deletePrincipal(String principal) throws KrbException {
        this.kadmin.deletePrincipal(principal);
    }

    public synchronized void exportPrincipal(String principal, File keytabFile) throws KrbException {
        this.kadmin.exportKeytab(keytabFile, principal);
    }
}
