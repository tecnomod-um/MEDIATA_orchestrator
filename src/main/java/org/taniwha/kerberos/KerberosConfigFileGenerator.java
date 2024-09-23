package org.taniwha.kerberos;

// Creates a kerberos config file
public class KerberosConfigFileGenerator {

    private KerberosConfigFileGenerator() {
    }

    public static String generateKrb5ConfContent(String realm, int kdcPort) {
        return "[libdefaults]\n"
                + "    default_realm = " + realm + "\n"
                + "    udp_preference_limit = 4096\n"
                + "    kdc_tcp_port = " + kdcPort + "\n"
                + "    kdc_udp_port = " + kdcPort + "\n"
                + "    allow_weak_crypto = true\n"
                + "    default_tkt_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96\n"
                + "    default_tgs_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96\n"
                + "    permitted_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96\n"
                + "    dns_lookup_realm = false\n"
                + "    dns_lookup_kdc = false\n"
                + "    ticket_lifetime = 24h\n"
                + "    renew_lifetime = 7d\n"
                + "    forwardable = true\n"
                + "\n"
                + "[realms]\n"
                + "    " + realm + " = {\n"
                + "        kdc = localhost:" + kdcPort + "\n"
                + "    }\n"
                + "\n"
                + "[domain_realm]\n"
                + "    .localhost = " + realm + "\n"
                + "    localhost = " + realm + "\n";
    }
}
