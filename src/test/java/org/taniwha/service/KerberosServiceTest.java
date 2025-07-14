package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.client.KrbClient;
import org.apache.kerby.kerberos.kerb.identity.backend.IdentityBackend;
import org.apache.kerby.kerberos.kerb.request.KrbIdentity;
import org.apache.kerby.kerberos.kerb.type.base.PrincipalName;
import org.apache.kerby.kerberos.kerb.type.kdc.EncAsRepPart;
import org.apache.kerby.kerberos.kerb.type.ticket.TgtTicket;
import org.apache.kerby.kerberos.kerb.type.ticket.Ticket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.taniwha.kerberos.CustomKdcServer;

import java.io.File;
import java.io.FileWriter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class KerberosServiceTest {

    private CustomKdcServer mockKdcServer;
    private KrbClient mockKrbClient;
    private IdentityBackend mockIdentityService;
    private KerberosService kerberosService;

    @BeforeEach
    void setUp() {
        mockKdcServer = mock(CustomKdcServer.class);
        mockIdentityService = mock(IdentityBackend.class);
        when(mockKdcServer.getIdentityService()).thenReturn(mockIdentityService);
        mockKrbClient = mock(KrbClient.class);
        kerberosService = new KerberosService(
                mockKdcServer,
                mockKrbClient,
                "TEST.REALM",
                "target/test-keytabs"
        );
    }

    @Test
    void testCreatePrincipal_WhenPrincipalDoesNotExist() throws KrbException {
        when(mockIdentityService.getIdentity("newUser")).thenReturn(null);
        kerberosService.createPrincipal("newUser", "password");
        verify(mockKdcServer, times(1)).createPrincipal("newUser", "password");
    }

    @Test
    void testCreatePrincipal_WhenPrincipalExists() throws KrbException {

        KrbIdentity existing = new KrbIdentity("existingUser");
        when(mockIdentityService.getIdentity("existingUser")).thenReturn(existing);
        kerberosService.createPrincipal("existingUser", "password");
        verify(mockKdcServer, never()).createPrincipal(anyString(), anyString());
    }

    @Test
    void testCreateKeytab_Success() throws KrbException {
        doAnswer(invocation -> {
            File fileArg = invocation.getArgument(1);
            File parent = fileArg.getParentFile();
            if (!parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter fw = new FileWriter(fileArg)) {
                fw.write("dummy keytab data");
            }
            return null;
        }).when(mockKdcServer).exportPrincipal(eq("myPrincipal"), any(File.class));

        String path = kerberosService.createKeytab("myPrincipal");

        assertNotNull(path, "Expected a non-null path");
        verify(mockKdcServer, times(1))
                .exportPrincipal(eq("myPrincipal"), any(File.class));
    }

    @Test
    void testRequestTgt_Success() throws Exception {

        TgtTicket mockTgt = mock(TgtTicket.class);
        Ticket mockTicket = mock(Ticket.class);
        when(mockTicket.encode()).thenReturn(new byte[]{1, 2, 3});
        when(mockTgt.getTicket()).thenReturn(mockTicket);

        EncAsRepPart mockEncAsRepPart = mock(EncAsRepPart.class);
        when(mockEncAsRepPart.encode()).thenReturn(new byte[]{4, 5, 6});
        when(mockTgt.getEncKdcRepPart()).thenReturn(mockEncAsRepPart);

        PrincipalName mockPrincipalName = mock(PrincipalName.class);
        when(mockPrincipalName.getName()).thenReturn("client@TEST.REALM");
        when(mockTgt.getClientPrincipal()).thenReturn(mockPrincipalName);

        when(mockKrbClient.requestTgt("testPrincipal", "testPassword"))
                .thenReturn(mockTgt);

        String encodedTicket = kerberosService.requestTgt("testPrincipal", "testPassword");
        assertNotNull(encodedTicket, "Should return a valid base64 ticket string");
    }

    @Test
    void testRequestTgt_Failure() throws KrbException {

        when(mockKrbClient.requestTgt("badPrincipal", "badPassword"))
                .thenThrow(new KrbException("Error requesting TGT"));
        String result = kerberosService.requestTgt("badPrincipal", "badPassword");
        assertNull(result);
    }
}
