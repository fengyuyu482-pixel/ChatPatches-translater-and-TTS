package obro1961.chatpatches.tts;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语音播放器 —— <b>不走 Minecraft 的音效系统，直接用 Java 的声卡接口</b>。
 *
 * <p>为什么不用 MC 的音频管线：那要先注册 SoundEvent、走资源包、受游戏音量控制，
 * 为一句 TTS 绕一大圈不值。这里直接播，自己配一个音量就行。
 *
 * <p>结构：一个<b>单线程队列</b> —— 保证同一时间只播一句，不会几句叠在一起；
 * 关掉游戏或点"停止"时能立刻清空。
 *
 * <p>支持两种输入：
 * <ul>
 *   <li><b>WAV</b>（自带格式头）—— 交给 {@link AudioSystem} 自己认</li>
 *   <li><b>裸 PCM</b> —— 按 OpenAI 的约定：<b>24000 Hz / 16 bit / 单声道 / 小端</b></li>
 * </ul>
 */
public final class AudioPlayer {

    /** OpenAI {@code pcm} 格式的约定。 */
    private static final float PCM_SAMPLE_RATE = 24000f;
    private static final int PCM_SAMPLE_SIZE_BITS = 16;
    private static final int PCM_CHANNELS = 1;

    private record Job(byte[] audio, boolean pcm) {
    }

    private final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(64);
    private final AtomicLong played = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private volatile boolean running = true;
    private volatile boolean muted = false;
    private volatile float volume = 1.0f;

    /** 当前正在播的那条（用于"停止"）。 */
    private volatile SourceDataLine current;
    private volatile long stopToken = 0;

    private final Thread worker;

    public AudioPlayer() {
        this("chatpatch-tts");
    }

    public AudioPlayer(String threadName) {
        worker = new Thread(this::loop, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    // ---- 对外 ----

    /** 入队一段音频。队列满就丢掉（宁可漏读一句，也不能卡住聊天线程）。 */
    public void enqueue(byte[] audio, boolean pcm) {
        if (audio == null || audio.length == 0 || muted) {
            return;
        }
        if (!queue.offer(new Job(audio, pcm))) {
            dropped.incrementAndGet();
        }
    }

    /** 立刻停下当前播放，并清空排队。 */
    public void stopAll() {
        stopToken++;
        queue.clear();
        SourceDataLine l = current;
        if (l != null) {
            try {
                // 顺序要紧：先 stop 让它停下，再 flush 丢掉缓冲里没播完的，
                // 最后才 close —— 只 flush 不 stop、或只 close 不 flush，
                // 都会把残留留在设备里，下次播放先冒出来（见 playOne 里的注释）。
                l.stop();
                l.flush();
                l.close();
            } catch (Exception ignored) {
                // 关不掉就算了
            }
        }
    }

    /** 静音开关（静音时新来的直接丢）。 */
    public void setMuted(boolean m) {
        this.muted = m;
        if (m) {
            stopAll();
        }
    }

    public boolean isMuted() {
        return muted;
    }

    /** 0.0 ~ 2.0，1.0 是原音量。 */
    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(2f, v));
    }

    public float volume() {
        return volume;
    }

    public int pending() {
        return queue.size();
    }

    public long playedCount() {
        return played.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    /** 关掉播放器线程。 */
    public void shutdown() {
        running = false;
        stopAll();
        worker.interrupt();
    }

    // ---- 内部 ----

    private void loop() {
        while (running) {
            Job job = null;
            try {
                job = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (job == null) {
                continue;
            }
            try {
                playOne(job);
                played.incrementAndGet();
            } catch (Exception ignored) {
                dropped.incrementAndGet();
            }
        }
    }

    private void playOne(Job job) throws Exception {
        long myToken = stopToken;
        // ★ 播放层留证据：音频字节数 + 格式 + token。排查"读什么都出同一句"时，
        // 需要区分"合成错了"还是"播放错了"。
        long t0 = System.currentTimeMillis();

        AudioInputStream in;
        if (job.pcm()) {
            AudioFormat fmt = new AudioFormat(PCM_SAMPLE_RATE, PCM_SAMPLE_SIZE_BITS, PCM_CHANNELS, true, false);
            in = new AudioInputStream(new ByteArrayInputStream(job.audio()), fmt, job.audio().length / (PCM_SAMPLE_SIZE_BITS / 8));
        } else {
            in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(job.audio()));
        }

        AudioFormat format = in.getFormat();
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            in.close();
            throw new IllegalStateException("声卡不支持这个格式: " + format);
        }

        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format);
        applyVolume(line);
        line.start();
        current = line;
        try {
            byte[] buf = new byte[4096];
            int n;
            while (myToken == stopToken && (n = in.read(buf)) > 0) {
                if (job.pcm()) {
                    applyGain16(buf, n);
                }
                line.write(buf, 0, n);
            }
            if (myToken == stopToken) {
                line.drain();
            }
        } finally {
            try {
                line.stop();
                // ★ 必须 flush，否则会"读出上一句"。
                //
                // Java Sound 的经典坑：stop() 之后缓冲区里还留着没播完的音频，
                // close() 并不会丢弃它们；下次 open 同一个设备时这些残留会先被放出来。
                // 实测表现：每读一句都先把第一句冒出来（"我说什么都先叫一声 nailong"），
                // 而且合成层日志完全正常 —— 因为音频根本没经过合成，是设备缓冲里的陈货。
                line.flush();
                line.close();
            } catch (Exception ignored) {
                // 忽略
            }
            current = null;
            in.close();
        }
    }

    /** 优先用声卡的音量控制。 */
    private void applyVolume(SourceDataLine line) {
        try {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl g = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                float db = volume <= 0.0001f ? g.getMinimum() : (float) (20.0 * Math.log10(volume));
                g.setValue(Math.max(g.getMinimum(), Math.min(g.getMaximum(), db)));
            }
        } catch (Exception ignored) {
            // 声卡不支持就靠 applyGain16（裸 PCM 那条路）
        }
    }

    /** 裸 PCM 时自己按音量缩放 16 位采样（小端）。 */
    private void applyGain16(byte[] buf, int len) {
        if (volume == 1.0f || volume <= 0f) {
            return;
        }
        for (int i = 0; i + 1 < len; i += 2) {
            int lo = buf[i] & 0xFF;
            int hi = buf[i + 1];
            int sample = (short) ((hi << 8) | lo);
            int v = (int) (sample * volume);
            v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
            buf[i] = (byte) (v & 0xFF);
            buf[i + 1] = (byte) ((v >> 8) & 0xFF);
        }
    }
}
