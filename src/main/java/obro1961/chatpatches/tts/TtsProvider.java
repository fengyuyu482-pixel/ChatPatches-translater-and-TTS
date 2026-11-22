package obro1961.chatpatches.tts;

import obro1961.chatpatches.translate.ProbeResult;

/**
 * 朗读引擎接口 —— <b>跟翻译一样，可替换</b>。
 *
 * <p>实现有两类：
 * <ul>
 *   <li>{@code api} —— 走云端 TTS（OpenAI / Azure / 别的），要 baseUrl + key</li>
 *   <li>{@code local} —— 本地引擎（系统自带语音），不用网络、不用 key</li>
 * </ul>
 */
public interface TtsProvider {

    /** 配置里那个名字。 */
    String id();

    /** 配置齐不齐、能不能用。 */
    boolean ready();

    /** 给人看的描述。 */
    String describe();

    /**
     * 合成一段语音。
     *
     * @param text 要读的文本（<b>原文</b>，不是译文）
     * @return 音频字节。<b>优先 wav</b>（自带格式头），其次裸 PCM
     * @throws Exception 失败就抛，交给上层降级到下一个引擎
     */
    byte[] synthesize(String text) throws Exception;

    /** 返回的音频是不是裸 PCM（没有头）。默认 false = wav。 */
    default boolean rawPcm() {
        return false;
    }

    /** 轻量连通性探测。不抛异常。 */
    ProbeResult probe();
}
