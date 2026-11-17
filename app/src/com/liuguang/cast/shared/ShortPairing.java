package com.liuguang.cast.shared;

import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant;
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups;
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload;
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload;
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;

/** J-PAKE with key confirmation over the TLS session used to store the TV's identity. */
public final class ShortPairing {
    private static final int GROUP_BYTES = 385, SCALAR_BYTES = 33;

    private ShortPairing() {}

    public static void confirm(DataInputStream in, DataOutputStream out, X509Certificate television,
                               String code, boolean receiver) throws IOException, GeneralSecurityException {
        // Fixed role-specific identifiers bind the exchange to the full TLS certificate fingerprint.
        String fingerprint = Pairing.fingerprintSha256(television);
        String phone = "liuguang-phone:" + fingerprint;
        String tv = "liuguang-tv:" + fingerprint;
        String peer = receiver ? phone : tv;
        JPAKEParticipant participant = new JPAKEParticipant(receiver ? tv : phone, code.toCharArray(),
                JPAKEPrimeOrderGroups.NIST_3072);

        try {
            JPAKERound1Payload round1 = participant.createRound1PayloadToSend();
            writeNumber(out, round1.getGx1());
            writeNumber(out, round1.getGx2());
            writeProof(out, round1.getKnowledgeProofForX1());
            writeProof(out, round1.getKnowledgeProofForX2());
            out.flush();
            participant.validateRound1PayloadReceived(new JPAKERound1Payload(peer, readGroup(in), readGroup(in),
                    readProof(in), readProof(in)));

            JPAKERound2Payload round2 = participant.createRound2PayloadToSend();
            writeNumber(out, round2.getA());
            writeProof(out, round2.getKnowledgeProofForX2s());
            out.flush();
            participant.validateRound2PayloadReceived(new JPAKERound2Payload(peer, readGroup(in), readProof(in)));

            BigInteger key = participant.calculateKeyingMaterial();
            JPAKERound3Payload confirmation = participant.createRound3PayloadToSend(key);
            if (!receiver) writeConfirmation(out, confirmation);
            participant.validateRound3PayloadReceived(new JPAKERound3Payload(peer, readNumber(in, SCALAR_BYTES)), key);
            if (receiver) writeConfirmation(out, confirmation);
        } catch (CryptoException | IllegalArgumentException | ArithmeticException e) {
            throw new GeneralSecurityException("四位投屏码验证失败", e);
        }
    }

    private static BigInteger readGroup(DataInputStream in) throws IOException {
        BigInteger element = readNumber(in, GROUP_BYTES);
        if (element.signum() <= 0) throw new IOException("投屏验证数据无效");
        return element;
    }

    private static BigInteger[] readProof(DataInputStream in) throws IOException {
        BigInteger commitment = readGroup(in);
        BigInteger response = readNumber(in, SCALAR_BYTES);
        if (response.signum() < 0) throw new IOException("投屏验证数据无效");
        return new BigInteger[] {commitment, response};
    }

    private static BigInteger readNumber(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readUnsignedShort();
        if (length < 1 || length > maxBytes) throw new IOException("投屏验证数据无效");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new BigInteger(bytes);
    }

    private static void writeNumber(DataOutputStream out, BigInteger number) throws IOException {
        byte[] bytes = number.toByteArray();
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static void writeProof(DataOutputStream out, BigInteger[] proof) throws IOException {
        for (BigInteger number : proof) writeNumber(out, number);
    }

    private static void writeConfirmation(DataOutputStream out, JPAKERound3Payload confirmation) throws IOException {
        writeNumber(out, confirmation.getMacTag());
        out.flush();
    }
}
