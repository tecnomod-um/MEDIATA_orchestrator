package org.taniwha.kerberos;

public class KerberosConfigFileGenerator {

    // Java 8 compatible (no text blocks)
    public static String generateKrb5ConfContent(String realm, int kdcPort, String kdcHost) {
        // Force TCP (helps avoid UDP weirdness in some Docker envs)
        int udpPreferenceLimit = 1;

        String sb = "[libdefaults]\n" +
                "    default_realm = " + realm + "\n" +
                "    udp_preference_limit = " + udpPreferenceLimit + "\n" +
                "    kdc_tcp_port = " + kdcPort + "\n" +
                "    kdc_udp_port = " + kdcPort + "\n" +
                "    allow_weak_crypto = true\n" +
                "    default_tkt_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96\n" +
                "    default_tgs_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96\n" +
                "    permitted_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96\n" +
                "    dns_lookup_realm = false\n" +
                "    dns_lookup_kdc = false\n" +
                "    ticket_lifetime = 24h\n" +
                "    renew_lifetime = 7d\n" +
                "    forwardable = true\n\n" +
                "[realms]\n" +
                "    " + realm + " = {\n" +
                "        kdc = " + kdcHost + ":" + kdcPort + "\n" +
                "    }\n\n" +
                "[domain_realm]\n" +
                "    ." + kdcHost + " = " + realm + "\n" +
                "    " + kdcHost + " = " + realm + "\n";

        return sb;
    }

    // Backward-compatible overload
    public static String generateKrb5ConfContent(String realm, int kdcPort) {
        return generateKrb5ConfContent(realm, kdcPort, "127.0.0.1");
    }
}
