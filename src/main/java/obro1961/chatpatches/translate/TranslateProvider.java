package obro1961.chatpatches.translate;

import java.util.List;

public interface TranslateProvider {

    String id();

    /** 配置齐不齐、能不能发请求。 */
    boolean ready();

    /** 给人看的描述，用于状态/日志。 */
    String describe();

    /**
     * 翻译一段文本。
     *
     * @param text           要翻译的正文
     * @param sourceLanguage 源语言，{@code auto} = 让模型自己认
     * @param targetLanguage 目标语言
     * @param context        参考上下文，只喂给模型，不要求翻译
     * @param glossaryHints  术语提示（{@code wb = 欢迎回来} 这种），让模型遵守固定译法
     * @param styleNote      额外风格要求，追加到提示词末尾；可为 null
     * @param timeoutMs      <b>本次调用允许用多少毫秒</b>；{@code <=0} 表示用配置里的默认值。
     *
     *                       <p><b>为什么要有这个参数</b>：只在上层"每轮开头检查一下还剩多少时间"是
     *                       封不住总时长的 —— 一轮请求自己就能跑满它自己的超时（配置里 8 秒），
     *                       于是"总预算 8 秒"实际会变成"最后一次尝试又跑满 8 秒"。
     *                       把本轮的超时也压到剩余时间内，总时长才真的封得住。
     * @return 译文；失败请抛异常，由上层决定降级
     */
    String translate(String text, String sourceLanguage, String targetLanguage,
                     List<String> context, List<String> glossaryHints, String styleNote,
                     long timeoutMs) throws Exception;

    /** 轻量连通性探测，用于启动自检。不抛异常，失败也要返回结果。 */
    ProbeResult probe();
}
