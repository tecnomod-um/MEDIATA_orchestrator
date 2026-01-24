package org.taniwha.kerberos;

public class KerberosConfigFileGenerator {

    // Java 8 compatible (no text blocks)
    public static String generateKrb5ConfContent(String realm, int kdcPort, String kdcHost) {
        // Force TCP (helps avoid UDP weirdness in some Docker envs)
        int udpPreferenceLimit = 1;

        StringBuilder sb = new StringBuilder();
        sb.append("[libdefaults]\n");
        sb.append("    default_realm = ").append(realm).append("\n");
        sb.append("    udp_preference_limit = ").append(udpPreferenceLimit).append("\n");
        sb.append("    kdc_tcp_port = ").append(kdcPort).append("\n");
        sb.append("    kdc_udp_port = ").append(kdcPort).append("\n");
        sb.append("    allow_weak_crypto = true\n");
        sb.append("    default_tkt_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96\n");
        sb.append("    default_tgs_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96\n");
        sb.append("    permitted_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96\n");
        sb.append("    dns_lookup_realm = false\n");
        sb.append("    dns_lookup_kdc = false\n");
        sb.append("    ticket_lifetime = 24h\n");
        sb.append("    renew_lifetime = 7d\n");
        sb.append("    forwardable = true\n\n");

        sb.append("[realms]\n");
        sb.append("    ").append(realm).append(" = {\n");
        sb.append("        kdc = ").append(kdcHost).append(":").append(kdcPort).append("\n");
        sb.append("    }\n\n");

        sb.append("[domain_realm]\n");
        sb.append("    .").append(kdcHost).append(" = ").append(realm).append("\n");
        sb.append("    ").append(kdcHost).append(" = ").append(realm).append("\n");

        return sb.toString();
    }

    // Backward-compatible overload
    public static String generateKrb5ConfContent(String realm, int kdcPort) {
        return generateKrb5ConfContent(realm, kdcPort, "127.0.0.1");
    }
}
