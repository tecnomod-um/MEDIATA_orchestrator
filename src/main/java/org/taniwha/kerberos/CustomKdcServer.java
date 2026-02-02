package org.taniwha.kerberos;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.admin.kadmin.local.LocalKadmin;
import org.apache.kerby.kerberos.kerb.admin.kadmin.local.LocalKadminImpl;
import org.apache.kerby.kerberos.kerb.identity.backend.BackendConfig;
import org.apache.kerby.kerberos.kerb.server.KdcConfig;
import org.apache.kerby.kerberos.kerb.server.KdcServer;

import java.io.File;

// Custom implementation to handle builtin principals on a preexisting backend
public class CustomKdcServer extends KdcServer {

    private LocalKadmin kadmin;

    public CustomKdcServer(KdcConfig kdcConfig, BackendConfig backendConfig) throws KrbException {
        super(kdcConfig, backendConfig);
        // DO NOT override realm/port/host here.
        // Those must come from KdcServerConfig / KdcConfig, or you will break client requests.
    }

    @Override
    public synchronized void init() throws KrbException {
        super.init();
        this.kadmin = new LocalKadminImpl(this.getKdcSetting(), this.getIdentityService());

        // Ensure builtin principals exist (krbtgt, kadmin, etc.)
        try {
            this.kadmin.checkBuiltinPrincipals();
        } catch (KrbException e) {
            this.kadmin.createBuiltinPrincipals();
        }
    }

    public synchronized void createPrincipal(String principal, String password) throws KrbException {
        if (this.kadmin.getPrincipal(principal) == null) {
            this.kadmin.addPrincipal(principal, password);
        }
    }

    public synchronized void deletePrincipal(String principal) throws KrbException {
        this.kadmin.deletePrincipal(principal);
    }

    public synchronized void exportPrincipal(String principal, File keytabFile) throws KrbException {
        this.kadmin.exportKeytab(keytabFile, principal);
    }
}
