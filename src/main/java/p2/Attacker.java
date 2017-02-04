package p2;

import common.Config;
import org.bouncycastle.pqc.math.linearalgebra.ByteUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static common.Utils.*;

public class Attacker {

    private Oracle oracle;

    public Attacker(Oracle oracle) {
        this.oracle = oracle;
    }

    private static Logger logger = LoggerFactory.getLogger(Attacker.class);

    private static final int BlockSize = getAlgoBlockSize(Config.DES);

    @SuppressWarnings({"unused", "Duplicates"})
    // only for attacker
    private List<byte[]> splitMsgBytes(byte[] msg) {
        List<byte[]> msgList = new ArrayList<byte[]>();
        int msgLength = msg.length;
        int lastBlockLength = msgLength % BlockSize;
        int initBlocks = msgLength - lastBlockLength;
        for (int i = 0; i < initBlocks; i += BlockSize) {
            byte[] block = Arrays.copyOfRange(msg, i, i + BlockSize);
            msgList.add(block);
        }
        // if lastBlockLength=0, no-op
        if (lastBlockLength != 0) {
            byte[] lastBlock = Arrays.copyOfRange(msg, initBlocks, msgLength);
            byte[] lastPadded = paddingBytes(lastBlock, BlockSize);
            msgList.add(lastPadded);
        }
        logger.info(dumpListsOfBytes(msgList));
        return msgList;
    }

    private List<byte[]> initOList() {
        List<byte[]> oList = new ArrayList<byte[]>();
        byte[] zero = new byte[BlockSize];
        Arrays.fill(zero, (byte) 0);
        oList.add(zero);
        return oList;
    }

    // oList.get(0) is NOT guaranteed to be same length as others
    private byte[] calculateMAC(List<byte[]> oList) {
        require(oList.size() >= 2, "oList.len should >=2");
        byte[] res = oList.get(1);
        for (int i = 2; i < oList.size(); ++i) {
            res = safeXor(res, oList.get(i));
        }
        return res;
    }

    private List<byte[]> crackImpl(byte[] input) {
        // calculate OO1, OO2, OO3 with mac0
        // BLK=8
        // z0 = [0x00 * BLK]
        // z0 for oo1, mac0(BLK) = oo1
        byte[] oo1 = oracle.mac0(1);
        // z0++z0 for oo2, mac0(2*BLK) = oo1 ⊕ oo2
        // oo2 = mac0(2*BLK) ⊕ oo1
        byte[] oo2 = safeXor(oracle.mac0(2), oo1);
        byte[] xor12 = safeXor(oo1, oo2);
        // split input into List; already padded
        List<byte[]> msgList = splitMsgBytes(input);
        // init oList
        List<byte[]> oList = initOList();
        /// fake encryption
        // for d = z0++z0++m
        byte[] twoBlockZeros = new byte[2 * BlockSize];
        for (int i = 0; i < msgList.size(); ++i) {
            byte[] oi = oList.get(i);
            // for d = [oo1, oo2, m ⊕ oo2]
            // E(m) = mac3(d) ⊕ oo1 ⊕ oo2
            byte[] m = safeXor(oi, msgList.get(i));
            byte[] d = safeXor(m, oo2);
            byte[] finalInput = concat(twoBlockZeros, d);
            require(finalInput.length == 3 * BlockSize,
                    String.format("input.len=%d, should be %d", finalInput.length, 3 * BlockSize));
            byte[] mac = oracle.mac3(finalInput);
            byte[] o = safeXor(mac, xor12);
            oList.add(o);
        }
        return oList;
    }

    public byte[] crack(byte[] input) {
        //        List<byte[]> oList = fakeEncrypt(input);
        List<byte[]> oList = crackImpl(input);
        logger.info(dumpListsOfBytes(oList));
        return calculateMAC(oList);
    }

    public static void main(String[] args) {
        Oracle oracle = new Oracle();
        Attacker attacker = new Attacker(oracle);
        String s = Config.p2INPUT;
        //        String s = "12345678";
        //        byte[] s = new byte[3 * 8];
        //        System.out.println(ppBytes(oracle.mac0(3)) + "\n" + ppBytes(oracle.mac3(s)));
        byte[] input = ByteUtils.fromHexString(s);
        byte[] res = attacker.crack(input);
        boolean matched = oracle.check(input, res);
        if (matched) {
            System.out.printf("%s\t%d\n%s\t%d\n",
                    ByteUtils.toHexString(input), input.length, ByteUtils.toHexString(res), res.length);
        } else {
            throw new RuntimeException("mismatch");
        }
    }

}
