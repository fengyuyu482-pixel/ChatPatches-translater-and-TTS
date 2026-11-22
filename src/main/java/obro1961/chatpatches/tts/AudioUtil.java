package obro1961.chatpatches.tts;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频小工具：WAV → PCM16 单声道 24000，重采样、拼接。
 *
 * <p>游戏播放器只认两种输入：WAV 或裸 PCM（24000/16bit/单声道）。
 * 多引擎采样率不同（Piper 22050、Kokoro 24000、SoVITS 32000），
 * 拼接前必须统一，否则变速变调。
 */
public final class AudioUtil {

    /** 目标采样率：与 AudioPlayer 的 PCM 约定一致。 */
    public static final int TARGET_RATE = 24000;

    private AudioUtil() {
    }

    /** 任意 WAV → 24000 单声道 16bit 裸 PCM。 */
    public static byte[] toPcm24k(byte[] wav) {
        Wav w = parse(wav);
        if (w == null) {
            return null;
        }
        short[] samples = w.samples;
        if (w.channels > 1) {
            samples = downmix(w.samples, w.channels);
        }
        if (w.rate != TARGET_RATE) {
            samples = resample(samples, w.rate, TARGET_RATE);
        }
        ByteBuffer out = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : samples) {
            out.putShort(s);
        }
        return out.array();
    }

    /** 多段裸 PCM 直接拼接。 */
    public static byte[] concat(List<byte[]> parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p == null ? 0 : p.length;
        }
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            if (p != null && p.length > 0) {
                System.arraycopy(p, 0, out, off, p.length);
                off += p.length;
            }
        }
        return out;
    }

    /** 段间插入静音（毫秒）。 */
    public static byte[] silence(int ms) {
        int frames = Math.max(0, ms) * TARGET_RATE / 1000;
        return new byte[frames * 2];
    }

    // ---------------- 内部 ----------------

    private static final class Wav {
        int rate;
        int channels;
        short[] samples;
    }

    private static Wav parse(byte[] b) {
        if (b == null || b.length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12;
        int rate = 0;
        int channels = 1;
        int bits = 16;
        int dataOff = -1;
        int dataLen = 0;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = buf.getInt(pos + 4);
            if ("fmt ".equals(id)) {
                channels = buf.getShort(pos + 10);
                rate = buf.getInt(pos + 12);
                bits = buf.getShort(pos + 22);
            } else if ("data".equals(id)) {
                dataOff = pos + 8;
                dataLen = Math.min(size, b.length - dataOff);
                break;
            }
            pos += 8 + size + (size & 1);
        }
        if (dataOff < 0 || bits != 16 || rate <= 0) {
            return null;
        }
        short[] s = new short[dataLen / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = buf.getShort(dataOff + i * 2);
        }
        Wav w = new Wav();
        w.rate = rate;
        w.channels = Math.max(1, channels);
        w.samples = s;
        return w;
    }

    private static short[] downmix(short[] in, int ch) {
        short[] out = new short[in.length / ch];
        for (int i = 0; i < out.length; i++) {
            int sum = 0;
            for (int c = 0; c < ch; c++) {
                sum += in[i * ch + c];
            }
            out[i] = (short) (sum / ch);
        }
        return out;
    }

    /** 线性插值重采样。 */
    private static short[] resample(short[] in, int from, int to) {
        if (in.length == 0) {
            return in;
        }
        int outLen = (int) Math.round(in.length * (double) to / from);
        short[] out = new short[outLen];
        double step = (double) from / to;
        for (int i = 0; i < outLen; i++) {
            double src = i * step;
            int i0 = (int) src;
            int i1 = Math.min(i0 + 1, in.length - 1);
            double frac = src - i0;
            out[i] = (short) Math.round(in[i0] * (1 - frac) + in[i1] * frac);
        }
        return out;
    }

    /** 供测试：把若干 PCM 段拼成 List。 */
    public static List<byte[]> list(byte[]... parts) {
        List<byte[]> l = new ArrayList<>();
        for (byte[] p : parts) {
            l.add(p);
        }
        return l;
    }
}
