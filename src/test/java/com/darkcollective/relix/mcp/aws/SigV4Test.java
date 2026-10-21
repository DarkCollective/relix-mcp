package com.darkcollective.relix.mcp.aws;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signer against AWS's own worked example of Signature Version 4: an IAM
 * {@code ListUsers} request, signed with AWS's example credentials.
 */
class SigV4Test {

    private static final Credentials EXAMPLE = new Credentials("AKIDEXAMPLE",
            "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", null, null);

    @Test
    void theSigningKeyIsAwssExample() {
        assertThat(SigV4.hex(new SigV4("us-east-1", "iam").signingKey(EXAMPLE.secretAccessKey(), "20150830")))
                .isEqualTo("c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9");
    }

    @Test
    void theSignatureIsAwssExample() {
        Map<String, String> signed = new SigV4("us-east-1", "iam").sign("GET",
                URI.create("https://iam.amazonaws.com/?Action=ListUsers&Version=2010-05-08"),
                Map.of("Content-Type", "application/x-www-form-urlencoded; charset=utf-8"),
                new byte[0], EXAMPLE, Instant.parse("2015-08-30T12:36:00Z"));

        assertThat(signed).containsEntry("X-Amz-Date", "20150830T123600Z")
                .containsEntry("Authorization", "AWS4-HMAC-SHA256 "
                        + "Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, "
                        + "SignedHeaders=content-type;host;x-amz-date, "
                        + "Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7")
                .doesNotContainKey("X-Amz-Security-Token");
    }

    @Test
    void temporaryCredentialsSignTheirSessionToken() {
        Map<String, String> signed = new SigV4("us-east-1", "dynamodb").sign("POST",
                URI.create("https://dynamodb.us-east-1.amazonaws.com/"), Map.of(), new byte[0],
                new Credentials("AKIDEXAMPLE", "secret", "the-token", null),
                Instant.parse("2015-08-30T12:36:00Z"));

        assertThat(signed).containsEntry("X-Amz-Security-Token", "the-token");
        assertThat(signed.get("Authorization")).contains("SignedHeaders=host;x-amz-date;x-amz-security-token");
    }
}
