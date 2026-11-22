package obro1961.chatpatches.gui;

import com.google.common.collect.Iterables;
import com.google.gson.JsonParseException;
import com.mojang.authlib.GameProfile;
import org.lwjgl.glfw.GLFW;
import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.GuiMessage;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import obro1961.chatpatches.accessor.ChatComponentAccess;
import obro1961.chatpatches.accessor.ChatScreenAccess;
import obro1961.chatpatches.mixin.gui.ChatScreenMixin;
import obro1961.chatpatches.util.RenderUtil;
import obro1961.chatpatches.util.TextUtil;
import obro1961.chatpatches.util.VersionUtil;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import oshi.util.Memoizer;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.minecraft.network.chat.CommonComponents.EMPTY;
import static net.minecraft.network.chat.Component.literal;
import static obro1961.chatpatches.ChatPatches.*;
import static obro1961.chatpatches.util.ChatUtil.*;

//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
//? if <1.20.2 {
//import net.minecraft.resources.ResourceLocation;
//?}
//? if >=1.21.9 {
import net.minecraft.world.entity.player.PlayerSkin;
//?} elif >=1.20.2 {
/*import net.minecraft.client.resources.PlayerSkin;
*//*?}*/

/**
 * Represents the context menu that appears when a chat message is right-clicked.
 * Controls the behavior and raw rendering of the menu buttons, as well as the
 * logic for utilizing, processing, and copying data from the selected message.
 */
public class ContextMenu implements GuiEventListener {
	static {
		MAX_ROWS = (int)FieldUtils.getAllFieldsList(ContextMenu.class).stream().filter(f -> f.getName().startsWith("MENU_") && f.getType().isInstance(EMPTY)).count();
	}

	public static final int MAX_ROWS;
	public static final int MAX_COLUMNS = 2; // this will probably never change
	public static final String LANG_PREFIX = "text.chatpatches.context.";

	private static final int BUTTON_HEIGHT = 14;
	private static final int BUTTON_PADDING = 4;
	/**
	 * Slightly modified from <a href="https://stackoverflow.com/a/163398">StackOverflow</a>
	 * to not include file links. Memoized to avoid recompiling the regex every time, and so
	 * it's only compiled when needed.
	 */
	private static final Supplier<Pattern> URL_PATTERN = Memoizer.memoize(() -> Pattern.compile("(?:https?://|www)[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]"));

	private static Minecraft mc() { return Minecraft.getInstance(); }
	private static MutableComponent translate(String key, Object... args) { return Component.translatable(LANG_PREFIX + key, args); }

	// region [IDs]
	static final Function<Object, Component> UNKNOWN = (id) -> translate("unknown", TextUtil.asText(id)).withStyle(ChatFormatting.RED);
	static final Component MENU_STRING = translate("copyText");
	/** 「复制 → 译文」（D16：有译文才出现）。 */
	static final Component TRANSLATION_TEXT = translate("translation");
	static final Component MENU_TIME = translate("time");
	static final Component TIMESTAMP = translate("timestampRaw");
	static final Component TIMESTAMP_HOVER = translate("timestampHoverText");
	static final Component FORMATTED_TIME = translate("formattedTime");
	static final Component UNIX = translate("unix");
	static final Int2ObjectFunction<Component> LINK_N = (n) -> translate("linkN", n);
	static final Component MENU_REPLY = translate("reply");
	static final Component MENU_DELETE = translate("delete");
	// chatstudy: 朗读这条 / 停止朗读。
	// 文案走 i18n（和整张菜单其它按钮同一个机制），图标走 registerActionButton 的 Item 参数 ——
	// 所以标签里【不放 emoji】：别的按钮都没有，放了就不一致了。
	static final Component MENU_READ_ALOUD = translate("readAloud");
	static final Component MENU_STOP_READING = translate("stopReading");
	/** R16 点击后翻译：手动翻这一条 */
	static final Component MENU_TRANSLATE = translate("translateNow");
	// ★ D16：菜单重做成 6 主项（+翻译=7）。复制只留 原文(JSON)/文本/译文/链接；时间三种格式。
	static final Component MENU_COPY = translate("copy");
	static final Component COPY_JSON = translate("copyJson");
	static final Component COPY_TEXT_ONLY = translate("copyPlain");
	static final Component DOT_TIME = translate("dotSeparated");
	static final Component PING = translate("ping");
	static final Component DM = translate("dm");
	// endregion

	/**
	 * Associates every widget with its relevant
	 * identifiers, which condenses accessing and
	 * mutating operations while also keeping
	 * ugly utility methods constrained in-scope
	 * and out of sight. Contains a positioning
	 * {@link GridLayout} and multiple lists for
	 * sorting and placing buttons in their
	 * intended locations.
	 */
	private final Grid grid;

	// reference variables kept primarily for rendering and elegance
	/**
	 * The ChatScreen this menu is contained within.
	 * If {@link #noOp} is {@code false}, this field
	 * is guaranteed to be non-null. However, if
	 * {@link #noOp} is {@code true}, this field may
	 * or may not be null.
	 */
	@Nullable
	private final ChatScreen screen;
	private final ChatComponent chat;

	// variables derived from selected message
	public final RenderUtil.MousePos clickPos;
	private final GuiMessage selectedLine;
	private final GameProfile messageSender;
	//~ render_extraction
	/**
	 * The index of the visible message in {@link ChatComponent#trimmedMessages}
	 * that is also {@linkplain GuiMessage.Line#endOfEntry EoE}. Used
	 * for simplifying and optimizing {@link #renderSelectionOutline(GuiGraphics)}
	 * and for calculating {@link #visibleLines}.
	 */
	private final int visibleMessageIndex;
	/**
	 * The number of visible lines in the selected message. Used for
	 * simplifying and optimizing {@link #renderSelectionOutline(GuiGraphics)}.
	 */
	private final int visibleLines;

	/** 选中消息在 allMessages 里的下标（打开菜单时记下）。
	 *  选中框每帧用它【重新算】位置 —— 行号会随新消息/滚动漂，下标不会（追加只在末尾）。 */
	private final int messageIndex;
	/** 当时是不是搜索模式（搜索结果的索引空间和 allMessages 不同，不能重算）。 */
	private final boolean searchMode;
	/** 选中消息"距末尾还有几条"：新消息插到头部时，下标会变、这个数不变。 */
	private final int fromBottom;
	/** 选中框诊断日志限流（1 秒一条，够定位又不刷屏）。 */
	private static long lastOutlineLog = 0L;

	/**
	 * If true, effectively disables this context menu, meaning
	 * it will do nothing when interacted with. This is used when
	 * a passed parameter is invalid, the context menu is not
	 * needed yet, it's disabled in the config, or for closing
	 * the menu.
	 */
	private boolean noOp = false;

	/**
	 * Creates a new context menu at the specified mouse position.
	 * Returns a {@linkplain #noOp non-operational} menu if either
	 * coordinate is negative, the selected message is invalid, or
	 * if the context menu is disabled in the config.
	 *
	 * <p>Not to be confused with {@link #init(Consumer)}, which
	 * populates, configures, and positions the button widgets.
	 *
	 * @see ChatScreenMixin#contextMenu
	 * @see ChatScreenMixin#mouseClickedEvents(MouseButtonEvent, boolean, CallbackInfoReturnable)
	 */
	public ContextMenu(ChatScreen screen, double mX, double mY) {
		if(!config.contextMenu || mX < 0 || mY < 0)
			noOp = true;

		// critical fields
		this.clickPos = RenderUtil.MousePos.of(mX, mY);
		this.grid = new Grid();

		// reference and optimization fields
		this.chat = mc().gui.getChat();
		var access = (ChatComponentAccess) chat;
		this.screen = noOp ? null : screen;

		// selected fields
		// ensures the search results are copied properly, when present
		var searchResults = noOp ? ObjectList.<GuiMessage>of() : ((ChatScreenAccess) screen).getSearchResults();
		var messages = searchResults.isEmpty() ? chat.allMessages : searchResults;
		int messageIndex = noOp ? -1 : access.getGuiMessageIndex(mX, mY);

		if(messageIndex == -1) {
			noOp = true;
		}

		this.messageIndex = messageIndex;
		this.fromBottom = (noOp || !searchResults.isEmpty()) ? -1
				: Math.max(0, chat.allMessages.size() - 1 - messageIndex);
		this.searchMode = !searchResults.isEmpty();
		this.selectedLine = noOp ? NIL_HUD_LINE : Iterables.get(messages, messageIndex, NIL_HUD_LINE);
		this.visibleMessageIndex = noOp ? -1 : message2Visible(messageIndex);

		// chatstudy: 右键定位埋点 —— 出问题时能一眼看出「点的是哪条、算出来的是哪条」。
		// 右键不常发生，一行 INFO 不刷屏；这行是「菜单和实际不同步」唯一的现场证据。
		if(!noOp) {
			String head = selectedLine.content().getString().replace('\n', ' ');
			if(head.length() > 40) {
				head = head.substring(0, 40) + "…";
			}
			obro1961.chatpatches.ChatPatches.LOGGER.info(
				"[chatpatch] 右键定位: y={} 滚动位置={} 消息序号={} 内容=[{}]",
				(int) mY, chat.chatScrollbarPos, messageIndex, head
			);
		}
		this.visibleLines = noOp ? 0 : Util.make(() -> {
			var visibles = chat.trimmedMessages;

			// if the line before (+) this visible message isn't EoE, we need to account for it(them?)
			int l = 1; // minimum of one line
			for(int j = visibleMessageIndex + 1; j < visibles.size() && !visibles.get(j).endOfEntry(); j++) {
				l++;
			}

			return l;
		});

		//~ !render_extraction
		this.messageSender = extractMessageSender(selectedLine.content());
	}


	/**
	 * Registers a button in the {@linkplain Grid#layout button grid} and
	 * {@linkplain #grid associated data manager}. This is done by
	 * creating a new {@link Button} (or similarly-implemented one if
	 * {@code icon} is specified) according to the passed id, copy text
	 * supplier, press action, and coordinates (the local row and
	 * absolute column).
	 *
	 * @param col The column in the grid menu where the button should be
	 * placed. Main buttons are always in column 0, and hover
	 * buttons are in columns ≥1.
	 * @param icon An {@link Item} or {@link PlayerSkin} object to
	 * render over the leftmost button area, or {@code null}
	 * to not render anything extra.
	 */
	private void registerButton(Component id, int col, Object icon, Supplier<Component> tooltipCopyTextSupplier, Button.OnPress pressAction) {
		int w = mc().font.width(id) + 2 * BUTTON_PADDING;
		int h = BUTTON_HEIGHT + BUTTON_PADDING;

		Button b = Button.builder(id, me -> {
			if(noOp) {
				return;
			}

			Component copyText = tooltipCopyTextSupplier != null ? tooltipCopyTextSupplier.get() : EMPTY;
			String copyStr = StringUtil.stripColor(copyText.getString());
			if(!copyStr.isEmpty()) {
				mc().keyboardHandler.setClipboard(copyStr);
				pushInfoToast(translate("copied").withStyle(ChatFormatting.GREEN), copyText);
			}

			if(pressAction != null) {
				pressAction.onPress(me);
			}
		}).bounds(clickPos.xInt(), clickPos.yInt(), w, h).build();

		if(icon != null) {
			// idea: make the button *not* adjust the text if it doesnt need to
			// accounts for the 16x16 icon on the left with the +16 and prefixed 4 spaces (each of width 4) in the id label
			b = new Button/*? if >=1.21.11 >> '('*/.Plain(b.getX(), b.getY(), b.getWidth() + 16, b.getHeight(), literal("    ").append(id), b.onPress, Button.DEFAULT_NARRATION) {
				/*
				 * Render replacements shouldn't be kept when applied to the versioned
				 * out comments; unfortunately, this is atrocious to do automatically.
				 */
				@Override //~ render_extraction
				protected void /*? if >=1.21.11 {*/renderContents/*?} else {*//*renderWidget*//*?}*/(GuiGraphics graphics, int mX, int mY, float delta) {
					/*? if >=1.21.11 {*/
					// both lines pulled from Button.Plain
					renderDefaultSprite(graphics);
					renderDefaultLabel(graphics.textRendererForWidget(this, GuiGraphics.HoveredTextEffects.NONE));
					/*?} else {*/
					/*super.renderWidget(graphics, mX, mY, delta);*/
					/*?}*/

					if(icon instanceof Item item) {
						graphics./*? if >=26.1 {*//*fakeItem*//*?} else {*/renderFakeItem/*?}*/(item.getDefaultInstance(), this.getX() + 1, this.getY() + 1);
					} else if(icon instanceof /*? if >=1.20.2 {*/PlayerSkin/*?} else {*//*ResourceLocation*//*?}*/ playerSkin) {
						/*? if >=26.1 {*//*PlayerFaceExtractor.renderRenderState*//*?} else {*/PlayerFaceRenderer.draw/*?}*/(graphics, playerSkin, this.getX() + 1, this.getY() + 1, 16);
					}
				}

				@Override
				public String toString() {
					// hopefully helps with debugging
					return "ContextMenu.IconButton[id=" + id.getString() + ", icon=" + icon + "]";
				}
			};
		}

		// set here so buttons with an icon don't have theirs deleted
		if(tooltipCopyTextSupplier != null) {
			b.setTooltip(Tooltip.create( tooltipCopyTextSupplier.get() )); //Text.of( tooltipCopyTextSupplier.get().getString().replace(Formatting.FORMATTING_CODE_PREFIX, '&') )
		}

		grid.add(b, col, tooltipCopyTextSupplier, pressAction);
	}

	/**
	 * Registers a <b>main</b> button that gets its copy text
	 * from {@code proxyId} and does <b>not</b> perform an
	 * extra press action. If provided, it draws the given object
	 * over the leftmost button area.
	 *
	 * @see #MENU_STRING
	 * @see #MENU_TIME
	 * @see #MENU_LINKS
	 * @see #MENU_SENDER
	 */
	private void registerProxyButton(Component id, Component proxyId, Object icon) {
		if(id.equals(proxyId)) {
			logReportMsg(new IllegalArgumentException("Cannot register proxy action button with own id '" + id.getString() + "'"));
			return;
		}

		// copies the proxy button's text by executing its press action instead
		//noinspection DataFlowIssue: parameter is never used anyway
		registerButton(id, 0, icon, null, me -> grid.get(proxyId).button.onPress(/*? if >=1.21.9 {*/null/*?}*/));
	}

	/**
	 * Registers a <b>main</b> button that <b>does</b> perform
	 * an extra press action. If provided, it draws the given
	 * icon over the leftmost button area.
	 *
	 * @see #MENU_REPLY
	 * @see #MENU_DELETE
	 */
	private void registerActionButton(Component id, Object icon, Button.OnPress pressAction) {
		registerButton(id, 0, icon, null, pressAction);
	}
	/**
	 * 注册一个<b>悬停才出现</b>的动作按钮（第 1 列）。
	 *
	 * <p>原来第 1 列只放"复制类"（copy），回复的 @名字 / /msg 是<b>填输入框</b>，
	 * 不是复制 —— 需要这个口子，否则只能把它们塞进第 0 列，破掉 D16 的两级结构。
	 */
	private void registerHoverActionButton(Component id, Button.OnPress pressAction) {
		registerButton(id, 1, null, null, pressAction);
	}

	/**
	 * 时间戳 → 点分隔格式（{@code 19.19.16}），供"复制时间"的第二种格式用。
	 *
	 * <p>时间来自时间戳样式里的 insertion（毫秒），不是字符串切片 ——
	 * 这样和其它两种格式（时间戳 / Unix）用的是<b>同一个时间源</b>，不会漂。
	 */
	/** 这条消息的"纯正文"：去时间戳、去名字、去我们追加的译文行。 */
	private static String textOnly(Component originalText) {
		if(originalText == null) {
			return "";
		}
		String body = obro1961.chatpatches.pipeline.ChatStudyPipeline.stripTimestamp(
			obro1961.chatpatches.pipeline.ChatStudyPipeline.plain(originalText.getString()));
		var parsed = obro1961.chatpatches.pipeline.ChatStudyPipeline.parse(null, body);
		String c = parsed.content();
		return c == null ? "" : c.trim();
	}

	private static Component dotTime(String millisText) {
		if(millisText == null || millisText.isEmpty()) {
			return UNKNOWN.apply(DOT_TIME);
		}
		try {
			long millis = Long.parseLong(millisText);
			return Component.literal(new SimpleDateFormat("HH.mm.ss").format(new Date(millis)));
		} catch(Exception e) {
			return UNKNOWN.apply(DOT_TIME);
		}
	}
	/**
	 * Registers a button that gets its copy text from
	 * a <b>supplier</b> and does <b>not</b> perform an
	 * extra press action. If provided, it draws the given
	 * icon over the leftmost button area.
	 *
	 * @see #registerCopyButton(Component, Component)
	 * @see #TIMESTAMP
	 * @see #TIMESTAMP_HOVER
	 * @see #FORMATTED_TIME
	 * @see #UNIX
	 */
	private void registerCopyButton(Component id, int col, Object icon, Supplier<Component> tooltipCopyTextSupplier) {
		registerButton(id, col, icon, tooltipCopyTextSupplier, null);
	}

	/**
	 * Registers a <b>hover</b> button with <b>precalculated</b>
	 * copy text that does <b>not</b> perform an extra press action.
	 *
	 * @see #registerCopyButton(Component, int, Object, Supplier)
	 */
	private void registerCopyButton(Component id, Component tooltipCopyText) {
		registerButton(id, 1, null, () -> tooltipCopyText, null);
	}


	/**
	 * Initializes the context menu by registering all buttons,
	 * their features, and by positioning everything correctly.
	 * Takes the {@link Screen#addWidget(GuiEventListener)}
	 * method and registers all buttons with it. Does nothing
	 * if the menu is {@linkplain #noOp disabled}.
	 *
	 * @implNote 注册顺序（= 显示顺序，D16）：
	 * <ol>
	 *     <li>{@link #MENU_COPY} ▸ 原文(JSON) / 文本 / 译文(有才出现) / 链接(有才出现)</li>
	 *     <li>{@link #MENU_TIME} ▸ 时间戳 / 点分隔 / Unix</li>
	 *     <li>{@link #MENU_REPLY} ▸ @名字 / /msg（有发送者或能从正文解析出名字才出现）</li>
	 *     <li>{@link #MENU_TRANSLATE}（R16）</li>
	 *     <li>{@link #MENU_READ_ALOUD} / {@link #MENU_STOP_READING}</li>
	 *     <li>{@link #MENU_DELETE}</li>
	 * </ol>
	 * Finally, updates and syncs the button positions and registers them
	 * with the {@link Screen#addWidget(GuiEventListener)} method.
	 *
	 * @see ChatScreenMixin#initSearchWidgets(CallbackInfo)
	 */
	public void init(Consumer<AbstractButton> addSelectableChild) {
		if(noOp) return;

		Component text = selectedLine.content();
		// ★ D13/A2：复制的是【原文】。我们追加的译文行带 insertion 标记，这里先剔掉：
		//   「原文（JSON）」必须是 100% 可还原的原文，里面混一行 ↳ 译文就不叫原文了；
		//   「文本」同理（D16：复制项不带译文）。时间戳/去重计数的下标不受影响 ——
		//   译文行永远追加在最后。
		Component originalText = TextUtil.withoutTranslation(text);
		Component timestamp = getPart(originalText, TIMESTAMP_INDEX);
		boolean timestamped = !timestamp.getString().isBlank();


		// ================= ★ D16 菜单重做（2026-10-20）=================
		// 主项：复制 / 时间 / 翻译这条 / 朗读 / 停止 / 删除 / 回复
		// 复制只留：原文(JSON) / 文本 / 译文(有才出现) / 链接(有才出现)
		// 时间三种：时间戳 / 点分隔 / Unix；回复两种：@名字 / /msg
		// 去掉的项：重复计数、发送者子项、无时间戳/无计数器/格式化变体（D16）

		// 链接先算好 —— 复制子菜单里要用（原来是独立主项）
		ObjectList<String> webLinks = Util.make(new ObjectArrayList<>(), l -> {
			Matcher matcher = URL_PATTERN.get().matcher(text.getString());
			while(matcher.find()) {
				l.add(matcher.group());
			}
		});
		ObjectList<String> filePaths = Util.make(new ObjectArrayList<>(), l ->
			text.visit((style, str) -> {
				//? if >=1.21.5 {
				if(style.getClickEvent() instanceof ClickEvent ce && (ce instanceof ClickEvent.OpenUrl || ce instanceof ClickEvent.OpenFile)) {
					boolean isUrl = ce instanceof ClickEvent.OpenUrl;
					String link = isUrl ? ((ClickEvent.OpenUrl)ce).uri().toString() : ((ClickEvent.OpenFile)ce).path();
					if(link != null && !link.isBlank()) {
						if(isUrl && !webLinks.contains(link)) {
							webLinks.add(link);
						} else if(!isUrl && !l.contains(link)) {
							l.add(link);
						}
					}
				}
				//?} else {
				/*if((Object)style.getClickEvent() instanceof ClickEvent ce && (Object)ce.getValue() instanceof String v && !v.isBlank()) {
					if(ce.getAction() == ClickEvent.Action.OPEN_URL && !webLinks.contains(v)) {
						webLinks.add(v);
					} else if(ce.getAction() == ClickEvent.Action.OPEN_FILE && !l.contains(v)) {
						l.add(v);
					}
				}
				*///?}
				return Optional.empty();
			}, Style.EMPTY));
		// ---- 头像 ----：玩家消息用皮肤；非玩家（Discord 转播等）用命名牌图标
		// 名字来源：① 消息自带 GameProfile ② 从正文抠（服务器/转播消息常见）
		String senderName = null;
		if( !NIL_SENDER.equals(messageSender) ) {
			senderName = messageSender./*? if >=1.21.9 {*/name/*?} else {*//*getName*//*?}*/();
		}
		if(senderName == null || senderName.isBlank()) {
			String rawBody = obro1961.chatpatches.pipeline.ChatStudyPipeline.stripTimestamp(
				obro1961.chatpatches.pipeline.ChatStudyPipeline.plain(text.getString()));
			int rawCut = rawBody.indexOf('\n');
			if(rawCut >= 0) {
				rawBody = rawBody.substring(0, rawCut);
			}
			var rawParsed = obro1961.chatpatches.pipeline.ChatStudyPipeline.parse(null, rawBody);
			senderName = rawParsed.speaker() == null ? null : rawParsed.speaker().trim();
		}
		Object avatarIcon = Items.NAME_TAG;
		if( !NIL_SENDER.equals(messageSender) ) {
			var senderId = messageSender./*? if >=1.21.9 {*/id/*?} else {*//*getId*//*?}*/();
			if(mc().getConnection() != null && mc().getConnection().getPlayerInfo(senderId) instanceof PlayerInfo info) {
				avatarIcon = info./*? if >=1.20.2 {*/getSkin/*?} else {*//*getSkinLocation*//*?}*/();
			}
		}
		if(Items.NAME_TAG.equals(avatarIcon) && senderName != null && !senderName.isBlank() && mc().getConnection() != null) {
			Object byName = skinByName(senderName);
			if(byName != null) {
				avatarIcon = byName;
			}
		}



		// ---- 1. 复制 ▸ ----（文本在前，原文（JSON）在后；一级默认 = 原文（JSON））
		// 一级「复制」默认 = 文本（纯正文），要原文 JSON 走二级菜单
		registerProxyButton(MENU_COPY, COPY_TEXT_ONLY, Items.OAK_SIGN);
			// 文本 = 只有正文（时间戳、名字、译文行全去掉）
			registerCopyButton(COPY_TEXT_ONLY, literal(textOnly(originalText)));
			registerCopyButton(COPY_JSON,
				TextUtil.UNSAFE_CODEC.encodeStart(VersionUtil.addSerializationContext(NbtOps.INSTANCE), originalText)
					.resultOrPartial(e -> logReportMsg(new JsonParseException(e)))
					.map(NbtUtils::toPrettyComponent)
					.orElse(UNKNOWN.apply(COPY_JSON))
			);
			String tr = TextUtil.translationOf(text);
			if(tr != null) {
				registerCopyButton(TRANSLATION_TEXT, literal(tr));
			}
			int linkIndex = 0;
			for(; linkIndex < filePaths.size(); linkIndex++) {
				registerCopyButton(LINK_N.apply(linkIndex + 1), literal(ChatFormatting.GOLD + "" + ChatFormatting.UNDERLINE + filePaths.get(linkIndex)));
			}
			int filePathOffset = linkIndex;
			for(; linkIndex - filePathOffset < webLinks.size(); linkIndex++) {
				registerCopyButton(LINK_N.apply(linkIndex + 1), literal(ChatFormatting.BLUE + "" + ChatFormatting.UNDERLINE + webLinks.get(linkIndex - filePathOffset)));
			}

		// ---- 2. 时间 ▸ ----
		registerProxyButton(MENU_TIME, timestamped ? TIMESTAMP : DOT_TIME, Items.CLOCK);
			if(timestamped) {
				registerCopyButton(TIMESTAMP, timestamp);
			}
			String unix = timestamp.getStyle().getInsertion();
			registerCopyButton(DOT_TIME, 1, null, () -> dotTime(unix));
			registerCopyButton(UNIX, 1, null, () ->
				unix != null && !unix.isEmpty() ? Component.literal(unix) : UNKNOWN.apply(UNIX)
			);

		// ---- 3. 回复 ▸（@名字 / /msg 名字）----
		// 名字：优先用上面解析出的发送者；拿不到（MIA 的 system / Discord 转播）就从正文抠。
		// 图标沿用同一份 avatarIcon：玩家=皮肤，非玩家=命名牌。
		String replyName = senderName;
		Object replyIcon = avatarIcon;
		if(replyName == null || replyName.isBlank()) {
			String body = obro1961.chatpatches.pipeline.ChatStudyPipeline.stripTimestamp(
				obro1961.chatpatches.pipeline.ChatStudyPipeline.plain(text.getString()));
			int cut = body.indexOf('\n');
			if(cut >= 0) {
				body = body.substring(0, cut);
			}
			var parsed = obro1961.chatpatches.pipeline.ChatStudyPipeline.parse(null, body);
			replyName = parsed.speaker() == null ? null : parsed.speaker().trim();
		}
		if(replyName != null && !replyName.isBlank()) {
			final String who = replyName;
			// 私聊在前、@ 在后；一级默认 = 私聊（DM）
			registerProxyButton(MENU_REPLY, DM, replyIcon);
				registerHoverActionButton(DM, me -> screen.input.setValue(TextUtil.fillVars(config.contextReplyFormat, who)));
				registerHoverActionButton(PING, me -> screen.input.setValue("@" + who + " "));
		}
		// ---- 4. 翻译这条（R16）----
		registerActionButton(MENU_TRANSLATE, Items.WRITABLE_BOOK, me ->
			obro1961.chatpatches.chatstudy.ChatStudyBridge.translateNow(text));

		// ---- 5. 朗读 / 停止 ----
		// 传【整串原文】，清洗交给 menuSpeech（切时间戳/译文行/颜色码/图标）
		registerActionButton(MENU_READ_ALOUD, Items.BELL, me ->
			obro1961.chatpatches.chatstudy.ChatStudyBridge.speakNow(text.getString()));
		registerActionButton(MENU_STOP_READING, Items.BARRIER, me ->
			obro1961.chatpatches.chatstudy.ChatStudyBridge.stopSpeaking());

		// ---- 6. 删除（二次确认 + 连带译文 + 写 delete{targetSeq}）----
		registerActionButton(MENU_DELETE, Items.BARRIER, me -> {
			if(config.contextDeletionWarning) {
				mc().setScreen(new DeletionWarningScreen(screen, selectedLine));
			} else {
				obro1961.chatpatches.chatstudy.ChatStudyBridge.noteDeletion(originalText);
				deleteMessage(selectedLine, config.contextDeletionSizzle);
			}
		});

		grid.updateButtonMetadata();
		grid.buttons().forEach(addSelectableChild);
		// ★ 2026-10-21 诊断：把这次真正注册出来的按钮按顺序打出来。
		//   作用：一眼看出"菜单是新的还是旧的"，不用靠截图猜。
		if(!noOp) {
			java.util.List<String> labels = new java.util.ArrayList<>();
			for(var b : grid.buttons()) {
				labels.add(b.getMessage().getString());
			}
			obro1961.chatpatches.ChatPatches.LOGGER.info("[chatpatch] 右键菜单已注册 {} 个按钮（按顺序）: {}", labels.size(), labels);
		}
	}

	/**
	 * Renders the context menu at the position specified by
	 * {@link #clickPos} and highlights its selected message
	 * in chat.
	 *
	 * @see #renderSelectionOutline(GuiGraphics)
	 * @see #renderMenuButtons(GuiGraphics, int, int, float)
	 *
	 * @see ChatScreenMixin#renderCustomWidgets(GuiGraphics, int, int, float, CallbackInfo)
	 */
	public void render(GuiGraphics graphics, int mX, int mY, float delta) {
		if(noOp) {
			return;
		}

		renderSelectionOutline(graphics/*, mX, mY, delta*/);
		renderMenuButtons(graphics, mX, mY, delta);
	}

	/**
	 * Renders a selection outline around the hovered message lines
	 * in the chat, to indicate which message will be copied.
	 */
	private void renderSelectionOutline(GuiGraphics graphics/*, int mX, int mY, float delta*/) {
		if(visibleLines == 0 || visibleMessageIndex == -1) {
			return;
		}

		// ★ 选中框位置【每帧重算】，不用打开菜单时缓存的行号。
		//   原因（用户实测）：复制出来的内容是对的，但箭头框歪了 —— 因为新消息进来 / 滚动 /
		//   我们给消息追加译文行，都会让"第几行"和真实渲染位置脱钩。
		//   这里改成按【消息下标】重新算 EoE 位置和它占几行，所以滚屏/插行都不再漂。
		// ★ 新消息会插到 allMessages 的【头部】，导致记下的下标指向别人 ——
		//   实测现象：来一条新消息，框就往上偏一格（动作结果还是对的，因为动作认的是对象）。
		//   这里每帧重新定位：先按对象找；对象被我们替换过（追加译文）就退回"距末尾几条"。
		int liveIndex = messageIndex;
		if(!searchMode) {
			liveIndex = -1;
			var all = chat.allMessages;
			for(int k = 0; k < all.size(); k++) {
				if(all.get(k) == selectedLine) {
					liveIndex = k;
					break;
				}
			}
			if(liveIndex < 0 && fromBottom >= 0) {
				liveIndex = Math.max(0, all.size() - 1 - fromBottom);
			}
			if(liveIndex < 0) {
				return;
			}
		}
		int eoe = searchMode ? visibleMessageIndex : obro1961.chatpatches.util.ChatUtil.message2Visible(liveIndex);
		if(eoe < 0) {
			return;
		}
		// ★ 行数要【往后数】。原来我往前走，结果只算到 1 行 —— 实测现象就是：
		//   两行消息（原文 + 译文）只圈住下面那行，上面那行没圈到。
		//   EoE 标在"这条消息的起点行"，同一条消息的其余行跟在它后面，直到下一个 EoE。
		var vis = chat.trimmedMessages;
		int hoveredParts = 1;
		while(eoe + hoveredParts < vis.size() && !vis.get(eoe + hoveredParts).endOfEntry()) {
			hoveredParts++;
		}
		if(searchMode) {
			hoveredParts = visibleLines;
		}
		double s = chat.getScale();
		int lH = chat.getLineHeight();
		int sW = Mth.ceil(chat.getWidth() / s); // scaled width
		int sH = Mth.floor((mc().getWindow().getGuiScaledHeight() - ChatComponent.BOTTOM_MARGIN) / s); // scaled height
		int shift = Mth.floor(config.calcDynamicChatShift() / s);
		int i = eoe - chat.chatScrollbarPos;
		if(System.currentTimeMillis() - lastOutlineLog > 1000L) {
			lastOutlineLog = System.currentTimeMillis();
			obro1961.chatpatches.ChatPatches.LOGGER.info(
				"[chatpatch] 选中框: liveIdx={} eoe={} lines={} scroll={} i={}",
				liveIndex, eoe, hoveredParts, chat.chatScrollbarPos, i);
		}
		int hoveredY = sH - (i * lH) - shift;

		graphics.pose().pushMatrix();
		graphics.pose().scale((float) s, (float) s /*? if <=1.21.5 {*//*, 1.0f*//*?}*/);

		int borderW = sW + 8;
		int scissorY1 = Mth.floor((sH - (chat.getLinesPerPage() * lH) - shift - 1) * s);
		int scissorY2 = Mth.floor((sH - shift + 1) * s);
		int selectionY1 = hoveredY - (lH * hoveredParts);
		int selectionH = (lH * hoveredParts) + 1;

		// cuts off any of the selection rect that goes past the chat hud
		graphics.enableScissor(0, scissorY1, borderW, scissorY2);
		graphics./*? if >=26.1 {*//*outline*//*?} else {*/renderOutline/*?}*/(0, selectionY1, borderW, selectionH, RenderUtil.opaque(config.contextOutlineColor));
		graphics.disableScissor();

		graphics.pose().popMatrix();
	}

	private void renderMenuButtons(GuiGraphics graphics, int mX, int mY, float delta) {
		grid.buttons().forEach(w -> w.render(graphics, mX, mY, delta));
	}
	/*~ !render_extraction*/


	/**
	 * If the tab key is pressed and the menu isn't disabled,
	 * checks if the chat screen is hovered/focused on a menu
	 * button, and if so {@linkplain #updateButtons(Optional)
	 * updates the buttons} accordingly. Otherwise, tries to
	 * press the selected button if it's part of the menu.
	 *
	 * @return {@code true} if the menu was successfully updated
	 * or if a button was pressed, otherwise {@code false} if the
	 * menu is {@linkplain #noOp disabled}.
	 *
	 * @see ChatScreenMixin#allowContextMenuKeyPressing(KeyEvent, CallbackInfoReturnable)
	 */
	@Override
	public boolean keyPressed(KeyEvent key) {
		if(noOp) {
			return false;
		}

		GuiEventListener focused = screen.getFocused();
		if(/*? if >=1.21.9 {*/ key.key() /*?} else {*//*keyCode*//*?}*/ == GLFW.GLFW_KEY_TAB) {
			if(focused instanceof AbstractButton tabbed && grid.contains(tabbed)) {
				updateButtons(Optional.of(tabbed));
				return true; // true - extra KeyCodes.isToggle check does NOT pass
			}
		}

		// true - extra KeyCodes.isToggle check DOES pass
		return grid.contains(focused) && focused.keyPressed(/*$ key_args {*/ key/*$}*/);
	}

	/**
	 * Handles the logic for triggering button click events,
	 * aka copying text, whenever the mouse is clicked.
	 *
	 * @return {@code true} if any of the menu buttons were
	 * 			clicked, {@code false} otherwise.
	 *
	 * @see ChatScreenMixin#mouseClicked(MouseButtonEvent, boolean)
	 */
	@Override
	public boolean mouseClicked(MouseButtonEvent mouse, boolean bl) {
		//? if >=1.21.9 {
		double mX = mouse.x(), mY = mouse.y();
		int button = mouse.button();
		//?}

		if(!noOp && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			Optional<AbstractButton> opt = getHoveredButton(mX, mY);
			// whether the button at (mX, mY) was clicked or not, otherwise return false and close the menu
			return opt.isPresent() && opt.get().mouseClicked(/*$ mouse_args {*/ mouse, bl/*$}*/);
		}

		return false; // signifies that the menu should be closed (clicked off)
	}

	/**
	 * Handles the logic for showing and hiding buttons when
	 * the mouse is moved over the context menu: Stores the
	 * result of calling {@link #getHoveredButton(double, double)}
	 * and checks if it's different from the currently focused
	 * element, and if so, passes the result to {@link
	 * #updateButtons(Optional)}. This optimizes the somewhat
	 * expensive, iterative {@link #updateButtons(Optional)} call
	 * by only calling it when a new button is hovered over.
	 *
	 * @see ChatScreenMixin#mouseMoved(double, double)
	 */
	@Override
	public void mouseMoved(double mX, double mY) {
		if(!noOp) {
			Optional<AbstractButton> opt = getHoveredButton(mX, mY);
			if(opt.orElse(null) != screen.getFocused())
				updateButtons(opt); // only update (and subsequently iterate through) every button if a new one is hovered over!
		}
	}

	public boolean isMouseOver(double mX, double mY) {
		return !noOp &&
			   mX >= grid.layout.getX() && mX <= grid.layout.getX() + grid.layout.getWidth()
			&& mY >= grid.layout.getY() && mY <= grid.layout.getY() + grid.layout.getHeight();
	}

	/**
	 * Marks the context menu {@linkplain #noOp disabled},
	 * unhooks all button widgets provided by this context
	 * menu (created in {@link #init(Consumer)}) from the
	 * screen, then clears all stored fields and focuses
	 * the {@link ChatScreen#input}. It must be focused
	 * at this specific time to ensure the focus call isn't
	 * ignored and delegated to a (now deleted) menu button.
	 */
	public void close(Consumer<AbstractButton> remove) {
		if(noOp) {
			return; // if the menu is already disabled, it was born broken, and therefore was never initialized
		}

		noOp = true;
		grid.buttons().forEach(remove);
		grid.clear();
		screen.setFocused(screen.input);
	}

	/**
	 * @see #noOp
	 *
	 * @apiNote Intended for external
	 * (non-{@link ContextMenu}) use.
	 */
	public boolean isFunctional() {
		return !noOp;
	}

	/**
	 * Signifies if the menu is focused to allow proper
	 * tab-navigation (aka forces the menu to update its
	 * buttons on keyboard input).
	 *
	 * @see #setFocused(boolean)
	 * @see #updateButtons(Optional)
	 *
	 * @return {@code true} if the menu is
	 * {@linkplain #noOp functional} and if the
	 * {@linkplain #MENU_STRING first button} is
	 * focused, {@code false} otherwise.
	 */
	@Override
	public boolean isFocused() {
		return !noOp && !grid.buttons().isEmpty() && grid.buttons().getFirst().isFocused();
	}

	/**
	 * Marks the {@linkplain #MENU_STRING first button} (and subsequently the
	 * context menu itself) focused according to the passed parameter. Does
	 * nothing if this menu is {@linkplain #noOp disabled}.
	 */
	@Override
	@Deprecated
	public void setFocused(boolean focused) {
		if(noOp) {
			return;
		}

		if(!grid.buttons().isEmpty()) {
			grid.buttons().getFirst().visitWidgets(MENU_STRING_BUTTON -> MENU_STRING_BUTTON.setFocused(focused));
		}
	}


	/**
	 * Updates the visibility of all buttons in the context menu,
	 * focuses the hovered button, and underlines the first hover
	 * button in each group if it exists.
	 *
	 * @see #mouseMoved(double, double)
	 * @see #keyPressed(KeyEvent)
	 *
	 * @implNote
	 * <ol>
	 *	 <li>If the passed {@link Optional} is empty, ensures nothing is focused
	 *	 on in the {@linkplain #screen chat screen} and returns</li>
	 *	 <li>Otherwise, focuses the hovered button in the chat screen</li>
	 *	 <li>Then iterates through every {@linkplain Grid#groups group} and every button in those groups:</li>
	 *	 <ol>
	 *	     <li>If the iterated button is {@linkplain Grid.Entry#isHover not a main button},
	 *	     sets its visibility based on whether the hovered button is in the iterated group or not</li>
	 *	     <li>If the iterated button is the hovered button and the iterated group has at least one hover
	 *	     button, toggles the {@link Style#underlined} attribute of the group's first hovered button
	 *	     based on whether {@linkplain Grid.Entry#isMain it should show} or hide (hover button aligned
	 *	     with its main button).</li>
	 *	 </ol>
	 * </ol>
	 */
	public void updateButtons(Optional<AbstractButton> widgetOptional) {
		if(widgetOptional.isEmpty()) {
			screen.setFocused(null); // removes the selected outline from the last hovered button
			return;
		}

		AbstractButton hoveredButton = widgetOptional.get();
		Component buttonText = hoveredButton.getMessage();
		Grid.Entry hoveredEntry = grid.get(buttonText);

		screen.setFocused(hoveredButton); // allows much more efficient update checks, see #mouseMoved(int, int)

		for(ObjectList<Grid.Entry> group : grid.groups) {
			for(Grid.Entry itr : group) {
				if(itr.isHover()) {
					// if the hovered button is in the group, show all other buttons; otherwise hide them bc they're irrelevant
					itr.button.visible = group.contains(hoveredEntry); // urgent: the arrow is messing up the id check - i think we should just uuid to this
				}

				// proceed with underlining if the hovered button is in the iterated group and the group has a hover button
				if(itr.button == hoveredButton && group.size() > 1 && /*? if java: <21 {*//*(Object)*//*?}*/ group.get(1).button instanceof AbstractButton firstHoverButton) {
					// remove if iterated button is in the group and the message is already underlined
					boolean hide = itr.isHover() && itr.row == group.getFirst().row;

					// note: removed `&& group.size() > 1` bc it's already true according to firstHoverButton's null check
					// show if iterated button is a main button and the message is not underlined
					boolean show = itr.isMain(); // main buttons need to underline their copy source!

					firstHoverButton.setMessage(firstHoverButton.getMessage().copy().withStyle( s -> s.withUnderlined(show || !hide) ));
				}
			}
		}
	}

	/**
	 * @return The button currently being hovered over, wrapped
	 * in an {@link Optional}. If the menu is {@linkplain #noOp
	 * disabled}, not hovered over, or if the parent screen wasn't
	 * specified, then an empty {@code Optional} is returned.
	 * Additionally, silently returns an empty optional if the
	 * hovered element is not a {@link AbstractButton}.
	 *
	 * @see ContainerEventHandler#getChildAt(double, double)
	 */
	private Optional<AbstractButton> getHoveredButton(double mX, double mY) {
		return isMouseOver(mX, mY) ? screen.getChildAt(mX, mY).map(e -> e instanceof AbstractButton p ? p : null) : Optional.empty();
	}


	/**
	 * Associates every widget with its relevant
	 * identifiers, which condenses accessing and
	 * mutating operations while also keeping
	 * ugly utility methods constrained in-scope
	 * and out of sight. Contains a positioning
	 * {@link GridLayout} and multiple lists for
	 * sorting and placing buttons in their
	 * intended locations.
	 */
	class Grid {
		private final GridLayout layout;
		private final ObjectList<Entry> entries;
		/**
		 * Holds a list of buttons at each index (group number) in the root list.
		 * The group number is used to determine which buttons should be visible
		 * when a button is hovered over.
		 * <p>NOTE: The only group that remains constant regardless of message
		 * contents is the first group ({@link #MENU_STRING}).
		 */
		private final ObjectList<ObjectList<Entry>> groups;

		/**
		 * The group index/id of the most recently added button. This is
		 * incremented every time a new main button (col = 0) is added to the
		 * grid. Incidentally, this field also refers to the current/absolute
		 * row of the most recently added button, and is used as such too.
		 *
		 * <p>Initialized to {@code -1} to prevent less readable edge-case
		 * carveouts in {@link #add(AbstractButton, int, Supplier, Button.OnPress)}
		 * (ex. {@code if(currentGroupAndRow == 0) {...} else {...}}).
		 */
		private int currentGroupAndRow = -1;
		private int localRow = 0;

		public Grid() {
			this.layout = new GridLayout(clickPos.xInt(), clickPos.yInt());
			this.entries = new ObjectArrayList<>(MAX_ROWS * MAX_COLUMNS);
			this.groups = new ObjectArrayList<>(MAX_ROWS);
		}

		public void add(AbstractButton button, int col, Supplier<Component> tooltipCopyTextSupplier, Button.OnPress pressAction) {
			boolean newGroup = button.visible = (col == 0); // if >1 main buttons are grouped together, things will break
			int entryRow = currentGroupAndRow; // assignment here to make the below else branch cleaner

			if(newGroup) {
				// main buttons signify a new group!
				entryRow = (++currentGroupAndRow);
				localRow = 0; // we don't need to add this to entryRow bc nothing would happen!
			} else {
				// hover buttons just keep moving down
				entryRow += (localRow++); // using the ++ here prevents every hover column from being shifted down one row!
			}

			Entry entry = new Entry(entryRow, col, currentGroupAndRow, button, tooltipCopyTextSupplier, pressAction);

			layout.addChild(button, entryRow, col);
			entries.add(entry);
			if(groups.size() > currentGroupAndRow) {
				groups.get(currentGroupAndRow).add(entry);
			} else {
				groups.add(currentGroupAndRow, new ObjectArrayList<>(ObjectArrayList.of(entry)));
			}
		}

		/**
		 * @return The {@link Entry} object associated with the given {@link Component}
		 * id, otherwise {@code null} if none exists.
		 *
		 * @implNote Tries to compare using the component contents, so different
		 * translation keys with equal resolved values still resolve as different.
		 * If the id component is not translatable, compares using {@link
		 * Component#getString()} because direct equality checks returned false negatives
		 * due to the styles occasionally being different (typically from the underlined
		 * button text).
		 *
		 * @author OBro1961, JustAlittleWolf
		 */
		public Entry get(Component id) {
			ComponentContents idContents = id.getContents();
			boolean checkTranslationKey = idContents instanceof TranslatableContents;

			for(Entry e : entries) {
				if(checkTranslationKey) {
					// must be a separate block otherwise messages with the same
					// display text will have the last registered one ignored
					// (see: FORMATTED_STR and FORMATTED_TIME)
					if(idContents.equals(e.id().getContents())) {
						return e;
					}
				} else {
					if(e.id().getString().equals(id.getString())) {
						return e;
					}
				}
			}

			return null;
		}

		@Contract("null -> false")
		public boolean contains(Object o) {
			// || (o instanceof Entry e && entries.contains(e)) // don't bother for the time being, this method is only ever passed instances of GuiEventListener
			return (o instanceof AbstractButton b && get(b.getMessage()) != null);
		}

		public void clear() {
			entries.clear();
			groups.clear();
			layout.children.clear();
		}

		/**
		 * @return The widgets stored in this Grid's internal {@link GridLayout}
		 * object, cast to <code>{@link List}<{@link AbstractButton}></code>.
		 * Will log a {@link ClassCastException} and return an empty list if any
		 * of the widgets are not of the correct type. However, this should never
		 * happen, per the {@linkplain
		 * ContextMenu#registerButton(Component, int, Object, Supplier, Button.OnPress)
		 * button registering methods}.
		 */
		@SuppressWarnings("unchecked")
		public List<AbstractButton> buttons() {
			try {
				return (List<AbstractButton>) (Object) layout.children/*? if >=26.1 {*//*.stream().map(wrapper -> wrapper.child).toList()*//*?}*/;
			} catch(ClassCastException e) {
				logReportMsg(e);
				return ObjectList.of();
			}
		}

		/**
		 * Aligns all buttons in a grid pattern in accordance with {@link
		 * GridLayout#arrangeElements()}. Synchronizes the widths of the main
		 * buttons (col 0) together, and the hover buttons (col 1) by group,
		 * so they are all the same width. Additionally, ensures the entire
		 * grid menu is visible on-screen by shifting it up and/or left if necessary.
		 */
		public void updateButtonMetadata() {
			layout.arrangeElements();

			// sync main button widths
			int mainWidth = entries.stream()
				.filter(Entry::isMain)
				.mapToInt(e -> e.button.getWidth())
				.max().orElse(8 * BUTTON_PADDING);
			entries.stream().filter(Entry::isMain).forEach(e -> e.button.setWidth(mainWidth));

			// sync hover button widths
			IntList groupWidths = IntArrayList.toList(
				groups.stream()
					.mapToInt(g -> g.stream()
						.skip(1) // avoid the main button
						.mapToInt(e -> e.button.getWidth()).max()
						.orElse(6 * BUTTON_PADDING)
					)
			);
			groups.forEach(g ->
				g.subList(1, g.size())
					.forEach(b ->
						b.button.setWidth(
							groupWidths.getInt(groups.indexOf(g))
						)
					)
			);


			// if the grid menu goes off the screen, shift it up
			int h = mc().getWindow().getGuiScaledHeight();
			if(layout.getHeight() + layout.getY() > h) {
				// moves the menu up by the amount it goes off the screen, plus a padding buffer
				layout.setY(h - layout.getHeight() - BUTTON_PADDING);
			}
			// if the grid menu goes off the screen, shift it left
			int w = mc().getWindow().getGuiScaledWidth();
			if(layout.getWidth() + layout.getX() > w) {
				// moves the menu left by the amount it goes off the screen, plus a padding buffer
				layout.setX(w - layout.getWidth() - BUTTON_PADDING);
			}
		}

		record Entry(int row, int col, int groupId, AbstractButton button, @NotNull Supplier<Component> tooltipCopyTextSupplier, @Nullable Button.OnPress pressAction) {
			boolean isMain() {
				return col == 0;
			}

			boolean isHover() {
				return col > 0;
			}

			Component id() {
				return button.getMessage();
			}
		}
	}

	/** 按玩家名字在在线列表里找皮肤；找不到返回 null（调用方回退命名牌）。 */
	private Object skinByName(String name) {
		if(name == null || name.isBlank() || mc().level == null) {
			return null;
		}
		for(var pl : mc().level.players()) {
			if(pl.getGameProfile()./*? if >=1.21.9 {*/name/*?} else {*//*getName*//*?}*/().equalsIgnoreCase(name.trim())) {
				if(mc().getConnection() != null && mc().getConnection().getPlayerInfo(pl.getUUID()) instanceof PlayerInfo info) {
					return info./*? if >=1.20.2 {*/getSkin/*?} else {*//*getSkinLocation*//*?}*/();
				}
			}
		}
		return null;
	}
}
