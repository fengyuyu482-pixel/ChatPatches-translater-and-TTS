//~ yarnification
package obro1961.chatpatches;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import obro1961.chatpatches.config.Config;
import obro1961.chatpatches.util.TextUtil;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static obro1961.chatpatches.util.TextUtil.asText;

public class ChatPatches implements ClientModInitializer {
	public static final String MOD_ID = "chatpatches";
	public static final Logger LOGGER = LoggerFactory.getLogger("Chat Patches");

	public static Config config = Config.initialize();

	public static Identifier id(String path) {
		return Identifier.tryBuild(MOD_ID, path);
	}


	@Override
	public void onInitializeClient() {
		// todo: gametests! somewhere somehow!

		// -- chatstudy: 语言注册表 --
		// ★ 必须在任何翻译路径之前加载：它决定「支持哪些语言」「每个 provider 能翻哪些」
		//   加载失败会打 ERROR 并让 translate 整体关闭，不做静默降级。
		obro1961.chatpatches.translate.LanguageRegistry.load();

		// -- chatstudy: 初始化我们这一套（翻译 / 记录 / 朗读）--
		obro1961.chatpatches.chatstudy.ChatStudyBridge.init(
			net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
		);

		// -- chatstudy: 出站翻译 --
		// 打中文 → 翻成英文再发。拦下原文异步翻，翻了再发（不卡游戏）。
		ClientSendMessageEvents.ALLOW_CHAT.register(text -> {
			if(obro1961.chatpatches.chatstudy.ChatStudyBridge.needsOutgoingTranslation(text)) {
				obro1961.chatpatches.chatstudy.ChatStudyBridge.sendTranslated(text);
				return false; // 拦下原文，翻译好后由我们发
			}
			return true;
		});
		// 出站记录：只在【消息真的发出去】时记一笔。
		//
		// 以前挂在 CHAT_CANCELED 上 —— 那是"被取消"的事件，语义就是反的。后果实测可见：
		//   · 被拦下的消息：CHAT_CANCELED 记一条原文，sendTranslated 又记一条译文 → 同一次发送写两条
		//   · 没被拦下的（英文）：CHAT_CANCELED 根本不触发 → 一条都不记
		// 改成 CHAT：不管是我们翻译后发的、还是原样发的，都恰好记一次，
		// 而且记的是【实际发出去的那句】。
		ClientSendMessageEvents.CHAT.register(text ->
			obro1961.chatpatches.chatstudy.ChatStudyBridge.onOutgoing(text));

		// chatstudy: 出站翻译的【命令】那一半 —— 带频道的发言。
		//
		// 有些服务器的全球频道要打成命令发（/g 内容）。命令走 ALLOW_COMMAND，
		// 【不经过】上面的 ALLOW_CHAT —— 打出去的中文就没人管了。
		// 用户实测就是这个现象：「/g 不行，/l 可以」（/l 是切模式后直接打字 = 普通聊天）。
		//
		// 只认配置 channelCommands 里列出的命令词，而且只翻命令词【之后】那一整段 ——
		// /msg Bob 你好 这种绝不能把收信人名字也翻掉。
		ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
			if(obro1961.chatpatches.chatstudy.ChatStudyBridge.needsCommandTranslation(command)) {
				obro1961.chatpatches.chatstudy.ChatStudyBridge.sendTranslatedCommand(command);
				return false; // 拦下原命令，翻译好后由我们发
			}
			return true;
		});

		// -- chat log saving events --
		// according to my testing, this event works as needed when the game disconnects and on crashes if the game is functional at that point
		// testing details (server=hypixel): normal disconnects work on both world and server, manual F3+C crash works on world but NOT server
		// honestly I don't care if it fails on crashes, its fixable A) through the save interval or B) by fixing the crash's source
		//stonecutter: * events *
		ClientPlayConnectionEvents.DISCONNECT.register((network, client) -> ChatLog.serialize());
		ScreenEvents.AFTER_INIT.register((client, screen, sW, sH) -> ChatLog.saveIfPaused(screen));
		ClientTickEvents./*? if >=26.1 {*//*END_LEVEL_TICK*//*?} else {*/END_WORLD_TICK/*?}*/.register(world -> ChatLog.tickSaveCounter());

		// chatstudy: 聊天栏改动队列的落地时机。
		//
		// ★ 全项目【唯一】允许修改聊天栏（allMessages / rescaleChat）的地方就在这里 ——
		//   挂译文、改造回显都只往队列里写一行，由这里统一落地。
		//
		//   为什么用 END_CLIENT_TICK 而不是上面那个 END_WORLD_TICK：
		//   后者只在世界里跑。译文可能在退出世界后才回来（断线、切服务器），
		//   用世界 tick 的话这类改动会一直压在队列里。客户端 tick 一直跑，没这个问题。
		ClientTickEvents.END_CLIENT_TICK.register(client -> obro1961.chatpatches.chatstudy.ChatStudyBridge.tick());

		// -- chat log loader and boundary sender --
		ClientPlayConnectionEvents.JOIN.register((network, packetSender, client) -> {
			// loads once
			ChatLog.load(false);

			// chatstudy: 切到新地点 —— 决定记录写到哪个文件夹，并写一行会话头
			obro1961.chatpatches.chatstudy.ChatStudyBridge.onJoin();

			// chatstudy: 把最近的记录灌回聊天栏（这就是"重启后还能往上翻"的原理）
			// ★ 2026-10-20：不再写死 2000 —— 读 5-record.json 的 restoreLimit（0 = 全灌），
			//   并且一次启动只灌一次（D6：世界只有一条时间线，换服不重贴）。
			obro1961.chatpatches.chatstudy.ChatStudyBridge.restoreRecent();

			/*? if >=1.21.9 {*/
			try {
				// provides a consistent value when accessing the config, and avoids #327
				// has to be done here because options isn't initialized at any earlier point :/
				boolean saveChatDrafts = Minecraft.getInstance().options.saveChatDrafts().get();
				// todo if there is any sort of onChanged callback i can register that'd be perfect
				if(config.onlyInvasiveDrafting != saveChatDrafts) {
					config.onlyInvasiveDrafting = saveChatDrafts;
				}
			} catch(Exception e) {
				//LOGGER.warn("Couldn't access options.saveChatDrafts:", e); // it's not that important
			}
			/*?}*/

			config.sendBoundaryLine(); // doesn't run the first time because of the slight delay before the chat log initializes messages
			ChatLog.hideRecentMessages();
		});

		LOGGER.info("Finished Chat Patches setup!");
	}


	/**
	 * @return The class and method of the caller, acquired from a {@link
	 * StackWalker}, formatted like {@code [$class.$method]}. Additionally, denotes
	 * lambda callers after the method name with {@code (->n)}, where {@code n} is
	 * the index of the lambda as written in the calling class, to make debugging
	 * easier. If the calling class is anonymous, hidden, or unnamed, it will be
	 * reflected in the output as its state surrounded by angle brackets (ex. {@code
	 * <hidden>}). If the method is a static initializer, it will be reflected as
	 * {@code <static_init>}; not to be confused with
	 * <a href="https://stackoverflow.com/questions/2420389/static-initialization-blocks">
	 * static initializer blocks</a>, which are automatically reflected as {@code
	 * <clinit>}.
	 *
	 * @implNote The lambda index is only added if the method name follows the pattern
	 * {@code lambda$method$n} or {@code lambda$static$n}, where {@code n} is the index
	 * of the lambda in the class. <b>This does not apply to mixin and/or nested
	 * lambdas</b> due to their sheer complexity (ex.
	 * {@code abcd6789$mod_id$lambda$method$n$m}) and rarity, so they are treated as
	 * regular methods.
	 *
	 * @param skipExtraFrame If true, skips the first two frames of the stack trace
	 *                       instead of just one. Intended for methods like {@link
	 *                       #logReportMsg(Throwable)} and {@link #logDuration(long, long)}
	 *                       that call this method themselves, so that the caller is
	 *                       reflected accurately.
	 */
	public static String getBracedCaller(boolean skipExtraFrame) {
		StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
		StackWalker.StackFrame caller = walker.walk(frames -> frames.skip(skipExtraFrame ? 2 : 1).findFirst().orElseThrow());
		String clazz = caller.getDeclaringClass().getSimpleName();
		String method = caller.getMethodName();
		String lambda = method.startsWith("lambda$") ? String.format("(->%s)", method.substring(method.lastIndexOf("$") + 1)) : "";

		if(clazz.isEmpty()) {
			Class<?> callingClass = caller.getDeclaringClass();
			if(callingClass.isAnonymousClass()) {
				clazz = "<anonymous>";
			} else if(callingClass.isHidden()) {
				clazz = "<hidden>";
			} else {
				clazz = "<unnamed>"; // preview feature so it goes in the else
			}
		}

		if(method.startsWith("lambda$static$")) { // goes first so it doesn't get masked by the next check
			method = "<static_init>";
		} else if(!lambda.isEmpty()) {
			method = method.substring(7, method.lastIndexOf("$")); // removes the 'lambda$' (l=7) and the '$n' at the end to get the method name
		}

		return String.format("[%s.%s%s]", clazz, method, lambda);
	}

	/**
	 * Logs an error-level message telling the user to report the given error.
	 * Caller details provided by {@link #getBracedCaller(boolean)}.
	 *
	 * <p>Outputs the following message:
	 * <pre>
	 * [$caller] /!\ Please report this error on GitHub or Discord with the full log file attached! /!\
	 * $error
	 * </pre>
	 */
	public static void logReportMsg(@NotNull Throwable error) {
		// logging the message regularly makes the logger treat the error like an Object and not a Throwable, so it doesn't print the stack trace -_-
		String message = getBracedCaller(true) + " /!\\ Please report this error on GitHub or Discord with the full log file attached! /!\\";
		LOGGER.error(message, error);
	}

	/**
	 * Logs how long the caller took to execute, according to {@code start} and the
	 * {@linkplain System#currentTimeMillis() current time}. If the duration is less
	 * than the given {@code threshold}, an info message is logged. Otherwise, logs a
	 * warning message noting the excessive duration and suggesting the user report
	 * it. In both cases the duration is converted to a {@code double} and logged in
	 * seconds.
	 *
	 * @param start The time, in milliseconds, when the caller started execution.
	 * Should be a previously recorded value from {@link System#currentTimeMillis()}.
	 * @param threshold The maximum duration, in milliseconds, that the caller should
	 * take to execute.
	 */
	public static void logDuration(long start, long threshold) {
		// store and convert to seconds
		double duration = (double) (System.currentTimeMillis() - start) / 1000;
		double max = (double) threshold / 1000;

		String caller = getBracedCaller(true);
		if(duration >= max) {
			LOGGER.warn("{} Took {} seconds, but should've taken less than {}s. Consider reporting this to the GitHub or Discord", caller, duration, max);
		} else {
			LOGGER.info("{} Took {} seconds", caller, duration);
		}
	}

	/**
	 * Submits the given task to an {@linkplain Util#ioPool() I/O worker
	 * thread} for execution, and returns a {@link CompletableFuture} for managing
	 * the results. If the task's results are not needed, perhaps in cases where
	 * exceptions are managed by the task itself or for saving actions, the returned
	 * {@link CompletableFuture} may be safely ignored.
	 *
	 * @apiNote Methods such as {@link CompletableFuture#orTimeout(long, TimeUnit)},
	 * by nature, <b>will block</b>, and as such should <b>probably not be used</b>.
	 */
	public static CompletableFuture<Void> executeIoTask(Runnable task) {
		final var IO_POOL = Util.ioPool(); // var for stonecutter!
		return CompletableFuture.runAsync(task, IO_POOL).exceptionallyAsync(e -> {
			logReportMsg(e);
			return null;
		}, IO_POOL);
	}

	private static void pushToast(boolean error, Object header, Object description) {
		//prepub: option to mute these. it should do the animated text tho like <original message> (wait 2 sec) 'Visit the config to mute toasts like this'
		final int MAX_LEN = 60; // minimizes errors going off-screen
		MutableComponent head = TextUtil.truncate(asText(header), MAX_LEN);
		MutableComponent desc = TextUtil.truncate(asText(description), MAX_LEN);

		if(!head.equals(asText(header))) {
			head.append(CommonComponents.ELLIPSIS.copy().withStyle(ChatFormatting.GRAY));
		}
		if(!desc.equals(asText(description))) {
			desc.append(CommonComponents.ELLIPSIS.copy().withStyle(ChatFormatting.GRAY));
		}
		/*if(ChatFormatting.stripFormatting(desc.getString()).length() > MAX_LEN) {
			desc = TextUtil.truncate(desc, MAX_LEN).append(CommonComponents.ELLIPSIS.copy().withStyle(ChatFormatting.GRAY));
		}*/

		Minecraft mc = Minecraft.getInstance();
		mc.execute(() ->
			SystemToast.add(
				mc./*? if >26.1.2 {*//*gui.toastManager*//*?} elif >=1.21.2 {*/getToastManager/*?} else {*//*getToasts*//*?}*/(),
				error
					/*~ if <=1.20.2 'Id' -> 'Ids' {*/
					? SystemToast.SystemToastId.PACK_LOAD_FAILURE
					: SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
					/*~ }*/
				head,
				desc
			)
		);
	}

	public static void pushErrorToast(Object header, Object description) {
		pushToast(true, header, description);
	}

	public static void pushInfoToast(Object header, Object description) {
		pushToast(false, header, description);
	}
}