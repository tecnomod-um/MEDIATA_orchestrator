package org.taniwha.service;

import lombok.Getter;
import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.client.KrbClient;
import org.apache.kerby.kerberos.kerb.identity.IdentityService;
import org.apache.kerby.kerberos.kerb.type.base.PrincipalName;
import org.apache.kerby.kerberos.kerb.type.kdc.EncAsRepPart;
import org.apache.kerby.kerberos.kerb.type.kdc.EncKdcRepPart;
import org.apache.kerby.kerberos.kerb.type.kdc.EncTgsRepPart;
import org.apache.kerby.kerberos.kerb.type.ticket.KrbTicket;
import org.apache.kerby.kerberos.kerb.type.ticket.SgtTicket;
import org.apache.kerby.kerberos.kerb.type.ticket.TgtTicket;
import org.apache.kerby.kerberos.kerb.type.ticket.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.kerberos.CustomKdcServer;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

// Kerberos-related functionality
@Service
public class KerberosService {

    private static final Logger logger = LoggerFactory.getLogger(KerberosService.class);

    private final CustomKdcServer kdcServer;

    private final KrbClient krbClient;

    @Getter
    private final String realm;

    private final String keyTabPath;

    public KerberosService(CustomKdcServer kdcServer, KrbClient krbClient,
                           @Value("${kerberos.realm}") String realm,
                           @Value("${kerberos.workdir}") String workDirPath) {
        this.krbClient = krbClient;
        this.kdcServer = kdcServer;
        this.realm = realm;
        this.keyTabPath = workDirPath;
    }

    // Creates a principal for each user
    public void createPrincipal(String principal, String password) throws KrbException {
        if (principalExists(principal)) {
            logger.warn("Tried creating already existing principal: {}", principal);
            return;
        }
        kdcServer.createPrincipal(principal, password);
        logger.debug("New principal created: {} {}", principal, password);
    }

    // Exports a keytab for a given principal
    public String createKeytab(String principal) throws KrbException {
        File workDir = new File(keyTabPath);
        File keytabFile = new File(workDir, principal.replace("/", "_") + ".keytab");
        kdcServer.exportPrincipal(principal, keytabFile);

        if (keytabFile.exists() && keytabFile.isFile() && keytabFile.length() > 0) {
            logger.debug("Keytab exported: {}", keytabFile.getAbsolutePath());
            return keytabFile.getAbsolutePath();
        } else {
            logger.error("Failed to create keytab file: {}", keytabFile.getAbsolutePath());
            return null;
        }
    }

    public void deletePrincipal(String principal) throws KrbException {
        if (principalExists(principal)) {
            kdcServer.deletePrincipal(principal);
            logger.debug("Principal deleted: {}", principal);

            Path keytabFilePath = Paths.get(keyTabPath, principal.replace("/", "_") + ".keytab");
            if (Files.exists(keytabFilePath)) {
                try {
                    Files.delete(keytabFilePath);
                    logger.debug("Keytab file deleted: {}", keytabFilePath.toAbsolutePath());
                } catch (IOException e) {
                    logger.error("Failed to delete keytab file: {}", keytabFilePath.toAbsolutePath(), e);
                }
            }
        } else {
            logger.warn("Principal does not exist: {}", principal);
        }
    }

    public String requestTgt(String userPrincipal, String password) {
        try {
            logger.debug("Requesting TGT for user principal: {} with password {}", userPrincipal, password);
            TgtTicket tgtTicket = krbClient.requestTgt(userPrincipal, password);
            logger.debug("TGT acquired for user principal: {}", userPrincipal);
            return encodeKrbTicket(tgtTicket);
        } catch (KrbException | IOException e) {
            logger.error("Error generating Kerberos TGT", e);
        }
        return null;
    }

    public String requestSgt(String userTgtToken, String servicePrincipal) throws IOException, KrbException {
        logger.debug("Requesting SGT ticket");
        TgtTicket tgtTicket = (TgtTicket) decodeKrbTicket(userTgtToken, true);
        SgtTicket sgtTicket = krbClient.requestSgt(tgtTicket, servicePrincipal);
        return encodeKrbTicket(sgtTicket);
    }

    private boolean principalExists(String principal) {
        try {
            IdentityService identityService = kdcServer.getIdentityService();
            return identityService.getIdentity(principal) != null;
        } catch (KrbException e) {
            logger.error("Error checking if principal exists", e);
            return false;
        }
    }

    private String encodeKrbTicket(KrbTicket krbTicket) throws IOException {
        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
             ObjectOutputStream objStream = new ObjectOutputStream(byteStream)) {

            byte[] ticketBytes = krbTicket.getTicket().encode();
            objStream.writeInt(ticketBytes.length);
            objStream.write(ticketBytes);

            byte[] encKdcRepPartBytes = krbTicket.getEncKdcRepPart().encode();
            objStream.writeInt(encKdcRepPartBytes.length);
            objStream.write(encKdcRepPartBytes);

            if (krbTicket instanceof TgtTicket) {
                TgtTicket tgtTicket = (TgtTicket) krbTicket;
                byte[] clientPrincipalBytes = tgtTicket.getClientPrincipal().getName().getBytes(StandardCharsets.UTF_8);
                objStream.writeInt(clientPrincipalBytes.length);
                objStream.write(clientPrincipalBytes);
            } else if (krbTicket instanceof SgtTicket) {
                SgtTicket sgtTicket = (SgtTicket) krbTicket;
                byte[] clientPrincipalBytes = sgtTicket.getClientPrincipal().getName().getBytes(StandardCharsets.UTF_8);
                objStream.writeInt(clientPrincipalBytes.length);
                objStream.write(clientPrincipalBytes);
            }

            objStream.flush();
            return Base64.getEncoder().encodeToString(byteStream.toByteArray());
        }
    }

    private KrbTicket decodeKrbTicket(String encodedTicket, boolean isTgt) throws IOException {
        byte[] combinedBytes = Base64.getDecoder().decode(encodedTicket);
        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(combinedBytes);
             ObjectInputStream objStream = new ObjectInputStream(byteStream)) {

            int ticketLength = objStream.readInt();
            byte[] ticketBytes = new byte[ticketLength];
            objStream.readFully(ticketBytes);
            Ticket ticket = new Ticket();
            ticket.decode(ticketBytes);

            int encKdcRepPartLength = objStream.readInt();
            byte[] encKdcRepPartBytes = new byte[encKdcRepPartLength];
            objStream.readFully(encKdcRepPartBytes);
            EncKdcRepPart encKdcRepPart = isTgt ? new EncAsRepPart() : new EncTgsRepPart();
            encKdcRepPart.decode(encKdcRepPartBytes);

            int clientPrincipalLength = objStream.readInt();
            byte[] clientPrincipalBytes = new byte[clientPrincipalLength];
            objStream.readFully(clientPrincipalBytes);
            PrincipalName clientPrincipal = new PrincipalName(new String(clientPrincipalBytes, StandardCharsets.UTF_8));

            if (isTgt) {
                return new TgtTicket(ticket, (EncAsRepPart) encKdcRepPart, clientPrincipal);
            } else {
                SgtTicket sgtTicket = new SgtTicket(ticket, (EncTgsRepPart) encKdcRepPart);
                sgtTicket.setClientPrincipal(clientPrincipal);
                return sgtTicket;
            }
        }
    }

    public String getPrincipalName(String ip, String realm) {
        String hostPrincipal = ip;
        String scheme = "";

        try {
            URI uri = new URI(ip);
            String host = uri.getHost();
            hostPrincipal = (host != null) ? host : ip;

            String uriScheme = uri.getScheme();
            if ("http".equalsIgnoreCase(uriScheme))
                scheme = "HTTP/";
            else if ("https".equalsIgnoreCase(uriScheme))
                scheme = "HTTPS/";
        } catch (URISyntaxException e) {
            logger.warn("Failed to parse URI from ip: {}. Using the original value.", ip, e);
        }
        return scheme + hostPrincipal + "@" + realm;
    }
}
