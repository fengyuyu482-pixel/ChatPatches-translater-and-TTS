package obro1961.chatpatches.util;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import obro1961.chatpatches.mixin.security.ClickEvent$ActionMixin;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A class containing various string and {@link Component} related utilities.
 */
public class TextUtil {
	/**
	 * 我们追加的「译文行」的 insertion 标记（D13/A2 的<b>原文锚点</b>）。
	 *
	 * <p><b>为什么用 insertion 而不是"记下追加前的 Component"</b>：追加译文时，
	 * {@code ChatStudyBridge.applyOp} 会用一个<b>新的</b> {@code GuiMessage} 替换旧的，
	 * 任何"以组件实例为键"的内存映射都会立刻失效（这正是 D13 最初想法的坑）。
	 * insertion 是<b>跟着组件一起走</b>的：消息怎么被替换、怎么被复制，
	 * 那一段的标记都还在 —— 搜索/复制只要看这个标记就能把它剔掉。
	 *
	 * <p>和 stock {@code Boundary} 用 insertion 标记分界线是同一个思路。
	 */
	public static final String TRANSLATION_INSERTION = "chatpatches:translation_line";

	/**
	 * 去掉我们追加的译文行，只留原文（搜索、复制用）。
	 *
	 * <p>stock 的搜索判据是 {@code msg.content().getString()} —— 我们把译文追加进了同一条
	 * Component，所以<b>搜译文会命中</b>（用户要求「搜索只搜原文/名字，不搜译文」，见 D13）。
	 * 复制项同理：D16 要求「原文（JSON）」= 可 100% 还原的原文，不能把译文带进去。
	 *
	 * <p>只剔除<b>顶层带标记的 sibling</b>，不动别的样式/内容；找不到标记就原样返回。
	 */
	public static MutableComponent withoutTranslation(Component c) {
		if (c == null) {
			return null;
		}
		MutableComponent out = c.copy();
		out.getSiblings().removeIf(s -> s != null
				&& TRANSLATION_INSERTION.equals(s.getStyle().getInsertion()));
		return out;
	}

	/** 这一整条消息里是否带了我们追加的译文行（有则可以复制"译文"项）。 */
	public static boolean hasTranslation(Component c) {
		if (c == null) {
			return false;
		}
		return c.getSiblings().stream().anyMatch(s -> s != null
				&& TRANSLATION_INSERTION.equals(s.getStyle().getInsertion()));
	}

	/**
	 * 造一行译文（带标记，供 {@link #withoutTranslation} 识别）。
	 *
	 * <h2>折行缩进</h2>
	 * <p>译文长了会被聊天框自动折行，而自动折行的后续行<b>顶到最左边</b>，
	 * 看起来和原文脱节。这里<b>自己预切行</b>，每行都带上缩进：
	 * <pre>
	 *   原文...
	 *        ↳ 这是一句很长的译文，
	 *          折行后和箭头对齐
	 * </pre>
	 * 首行前缀 {@code "     ↳ "}（5 空格 + 箭头 + 空格），
	 * 续行前缀 {@code "       "}（7 空格，正好对齐到箭头的下一格）。
	 *
	 * <p><b>为什么自己切而不靠 MC 自动折行</b>：MC 的换行发生在一个
	 * {@code Component.literal} 内部时，只有第一物理行吃到那个 {@code \n     } 缩进；
	 * 后续折行处不在 literal 范围内，缩进就丢了。所以必须显式切。
	 *
	 * <p><b>宽度怎么估</b>：用 {@link #approxWidth} 按"全角 2 / 半角 1"折算，
	 * 而不是简单的字符数 —— 中文一个字符占两个英文宽，按字符数切会切得太长。
	 * 目标宽度取 {@code Config.chatWidth}，拿不到就用 320（原版默认）。
	 */
	public static MutableComponent translationLine(String translation) {
		if (translation == null || translation.isBlank()) {
			return Component.literal("").withStyle(s -> s.withInsertion(TRANSLATION_INSERTION));
		}

		// 首行："     ↳ "  = 5 空格 + 箭头 + 空格，共约 7 个半角宽
		// 续行："       "  = 7 空格，对齐到箭头之后
		final String firstPrefix = "     §7↳ §f";
		final String restPrefix = "       ";
		final String firstPlain = "     ↳ ";   // 宽度计算用（不含颜色码）
		final String restPlain = "       ";

		int maxWidth = chatLineWidth();          // 整行可用宽度（半角单位）
		int firstAvail = Math.max(8, maxWidth - approxWidth(firstPlain));
		int restAvail = Math.max(8, maxWidth - approxWidth(restPlain));

		List<String> lines = wrapByWidth(translation, firstAvail, restAvail);

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.size(); i++) {
			if (i == 0) {
				sb.append('\n').append(firstPrefix);
			} else {
				sb.append('\n').append(restPrefix);
			}
			sb.append(lines.get(i));
		}
		return Component.literal(sb.toString())
				.withStyle(s -> s.withInsertion(TRANSLATION_INSERTION));
	}

	/**
	 * 聊天框一行能放多少「半角单位」。
	 *
	 * <p>原版默认 {@code chatWidth = 320} 像素，字号大约每半角字符 6 像素，
	 * 所以 320 / 6 ≈ 53 个半角字符。这里用 {@code chatWidth} 换算，
	 * 拿不到配置就按 320 算。
	 */
	private static int chatLineWidth() {
		try {
			int px = obro1961.chatpatches.ChatPatches.config.chatWidth;
			if (px <= 0) {
				px = 320;   // Config.chatWidth <= 0 时用原版默认
			}
			// 6px 一个半角字符；留 2 个字符余量防止边缘折行
			return Math.max(20, px / 6 - 2);
		} catch (Throwable t) {
			return 51;   // 320/6-2
		}
	}

	/**
	 * 按显示宽度切行。
	 *
	 * <p>尽量在<b>空格处</b>断开（英文按词、中文按字），断不了就硬切。
	 *
	 * @param firstAvail 首行可用宽度（半角单位）
	 * @param restAvail  续行可用宽度
	 */
	private static List<String> wrapByWidth(String text, int firstAvail, int restAvail) {
		List<String> out = new ArrayList<>();
		String remaining = text;
		int avail = firstAvail;

		while (!remaining.isEmpty()) {
			if (approxWidth(remaining) <= avail) {
				out.add(remaining);
				break;
			}
			// 找出能放下的最大前缀
			int cut = 0;
			int w = 0;
			while (cut < remaining.length()) {
				int cp = remaining.codePointAt(cut);
				int cw = isWide(cp) ? 2 : 1;
				if (w + cw > avail) {
					break;
				}
				w += cw;
				cut += Character.charCount(cp);
			}
			if (cut <= 0) {
				cut = Character.charCount(remaining.codePointAt(0));
			}
			// 回退到最近的空格（只在 ASCII 空格上退，避免打乱中日韩）
			int spaceCut = remaining.lastIndexOf(' ', cut);
			if (spaceCut > cut / 2 && spaceCut > 0) {
				out.add(remaining.substring(0, spaceCut));
				remaining = remaining.substring(spaceCut + 1);
			} else {
				out.add(remaining.substring(0, cut));
				remaining = remaining.substring(cut);
			}
			avail = restAvail;
		}
		return out;
	}

	/** 估算字符串占几个「半角单位」。 */
	private static int approxWidth(String s) {
		int w = 0;
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			w += isWide(cp) ? 2 : 1;
			i += Character.charCount(cp);
		}
		return w;
	}

	/** 这个码点是不是"全角/宽"字符（中日韩、全角标点等）。 */
	private static boolean isWide(int cp) {
		return (cp >= 0x1100 && cp <= 0x115F)      // 谚文字母
			|| (cp >= 0x2E80 && cp <= 0xA4CF)      // 中日韩部首/假名/汉字
			|| (cp >= 0xAC00 && cp <= 0xD7A3)      // 谚文音节
			|| (cp >= 0xF900 && cp <= 0xFAFF)      // 兼容汉字
			|| (cp >= 0xFE30 && cp <= 0xFE6F)      // 兼容形式
			|| (cp >= 0xFF00 && cp <= 0xFF60)      // 全角形式
			|| (cp >= 0xFFE0 && cp <= 0xFFE6)
			|| (cp >= 0x1F300 && cp <= 0x1F9FF)    // emoji
			|| (cp >= 0x20000 && cp <= 0x3FFFD);   // 扩展 CJK
	}

	/**
	 * 取出这条消息上追加的译文文字（没有就 {@code null}）。
	 *
	 * <p>给右键菜单的「复制 → 译文」用（D16）。返回的是<b>纯译文</b>：
	 * 去掉每行开头的换行、缩进和 {@code §7↳ §f} 颜色码，再把多行接回一行 ——
	 * 复制出去不该带排版垃圾。
	 *
	 * <p>★ 折行缩进上线后，一条译文可能是多行（每行都有 {@code "       "} 缩进），
	 * 所以这里要<b>逐行</b>清理，不是只在开头清理一次。
	 */
	public static String translationOf(Component c) {
		if (c == null) {
			return null;
		}
		for (Component s : c.getSiblings()) {
			if (s == null || !TRANSLATION_INSERTION.equals(s.getStyle().getInsertion())) {
				continue;
			}
			String raw = s.getString();
			if (raw == null || raw.isBlank()) {
				return null;
			}
			// 先剥颜色码，再逐行去掉「缩进 + 箭头」
			String plain = ChatFormatting.stripFormatting(raw);
			StringBuilder sb = new StringBuilder();
			for (String line : plain.split("\n", -1)) {
				String t = line.replaceFirst("^\\s*↳\\s*", "");   // 首行：缩进 + 箭头
				t = t.replaceFirst("^\\s+", "");                  // 续行：只有缩进
				if (!t.isEmpty()) {
					if (sb.length() > 0) {
						// 英文按空格接，中文直接接（判断上一行末尾和这行开头是不是 CJK）
						char prev = sb.charAt(sb.length() - 1);
						char next = t.charAt(0);
						boolean prevCjk = isWide(prev);
						boolean nextCjk = isWide(next);
						if (!prevCjk && !nextCjk) {
							sb.append(' ');
						}
					}
					sb.append(t);
				}
			}
			String out = sb.toString().trim();
			return out.isBlank() ? null : out;
		}
		return null;
	}


	/**
	 * @see ChatFormatting#PREFIX_CODE
	 */
	public static final Matcher AMPERSAND_REGEX = Pattern.compile("(?m)&([\\da-fk-or])").matcher("");
	/**
	 * {@link #AMPERSAND_REGEX} that explicitly does not match any
	 * formatting codes following backslashes
	 */
	public static final Matcher NO_BACKSLASH_AMPERSAND_REGEX = Pattern.compile("(?m)(?<!\\\\)&([\\da-fk-or])").matcher("");
	/**
	 * Matches two ampersand color codes that directly follow one another. Will
	 * also match if there's any whitespace in between them. This is used to
	 * simplify output strings, as in these cases the first color code is entirely
	 * useless. When replacing, {@code $1} becomes the captured whitespace, if any,
	 * and {@code $2} becomes the effective color code (not including the ampersand).
	 *
	 * @see <a href="https://regex101.com/r/D9x2yv/latest">Examples</a>
	 */
	public static final Matcher REDUNDANT_COLOR_REGEX = Pattern.compile("(?m)&(?:#[\\da-f]{6}|[\\da-f])(\\s*)&(#[\\da-f]{6}|[\\da-f])").matcher("");

	/**
	 * Matches any ampersand formatting codes, whether regular (legacy) or hex,
	 * for use with the pretty-printer in {@link #toLegacyString(Component, boolean)}.
	 * Captures all formatting code(s) present in a string. Note that if any are
	 * back-to-back, they will be captured together as one string.
	 *
	 * @see <a href="https://regex101.com/r/m0AM81/latest">Examples</a>
	 */
	public static final Matcher PRETTY_PRINT_TARGETS_REGEX = Pattern.compile("(?im)(?<!\\\\)(&(?:#[\\da-f]{6}|[\\da-fk-or]))+").matcher("");


	/**
	 * A wrapped {@link Codec} for {@link Component} objects that will not
	 * throw an exception when serializing click events with unsafe
	 * ({@link ClickEvent.Action#OPEN_FILE}) actions. Done by disabling the
	 * {@linkplain #safeCodec safety serialization check} that is used by
	 * {@link ClickEvent$ActionMixin} to temporarily allow all
	 * messages to be serialized.
	 *
	 * @see ClickEvent$ActionMixin#allowConditionalSerialization(boolean)
	 * @see obro1961.chatpatches.ChatLog#CODEC
	 */
	public static final Codec<Component> UNSAFE_CODEC = new Codec<>() {
		/*~ if <=1.20.2 'ComponentSerialization.CODEC' -> 'net.minecraft.util.ExtraCodecs.COMPONENT' {*/
		@Override
		public <T> DataResult<T> encode(Component input, DynamicOps<T> ops, T prefix) {
			safeCodec.set(false);
			var result = ComponentSerialization.CODEC.encode(input, ops, prefix);
			safeCodec.set(true);
			return result;
		}

		@Override
		public <T> DataResult<Pair<Component, T>> decode(DynamicOps<T> ops, T input) {
			safeCodec.set(false);
			var result = ComponentSerialization.CODEC.decode(ops, input);
			safeCodec.set(true);
			return result;
		}

		@Override
		public String toString() {
			return "UnsafeTextCodec[safe=" + safeCodec.get() + ", codec=" + ComponentSerialization.CODEC + "]";
		}
		/*~}*/
	};

	/**
	 * A boolean-wrapped holder that is {@code true} when safety mechanisms
	 * <b>should</b> be used when serializing text components, and {@code false}
	 * otherwise. Allows for the serialization of potentially unsafe objects
	 * like {@link ClickEvent}s.
	 *
	 * @see ClickEvent$ActionMixin#allowConditionalSerialization(boolean)
	 *
	 * @implNote Thread-local because
	 * <a href="https://discord.com/channels/507304429255393322/721100785936760876/1387226885867704401">
	 * TheWhyEvenHow</a> suggested this and its (successful) implementation.
	 */
	public static final ThreadLocal<Boolean> safeCodec = ThreadLocal.withInitial(() -> true);


	/**
	 * Replaces all {@code $} characters in {@code str} with {@code variable}.
	 * Also replaces intended newline characters with {@code \n} to fix (#36).
	 */
	public static String fillVars(String str, String variable) {
		return str.replace("$", variable).replace("\\n", "\n");
	}

	/**
	 * Parses the given object into a {@link MutableComponent}.
	 */
	public static MutableComponent asText(Object o) {
		if(o == null) {
			return Component.empty();
		} else if(o instanceof Component) {
			return (MutableComponent) o;
		} else {
			return Component.literal(o instanceof FormattedText ft ? ft.getString() : String.valueOf(o));
		}
	}

	/**
	 * Walk the component-sibling tree in depth first order (aka render order).
	 * Similar to {@code Component.visit()} but without using
	 * {@code Component.getContents().visit()}, i.e. it will not decompose
	 * translatables into parts.
	 */
	public static void walkTree(Component component, Style currentStyle, BiConsumer<Component, Style> consumer) {
		currentStyle = component.getStyle().applyTo(currentStyle);

		consumer.accept(component, currentStyle);

		for (Component sibling : component.getSiblings()) {
			walkTree(sibling, currentStyle, consumer);
		}
	}

	/**
	 * Turns the component-sibling tree into a flat list.
	 * Similar to {@code Component.toFlatList()} but using {@link #walkTree}
	 * instead of {@code Component.visit()}.
	 */
	public static List<Component> linearize(Component component) {
		List<Component> components = new ArrayList<>();

		walkTree(component, Style.EMPTY, (c, cStyle) -> {
			// plainCopy() just copies the contents, so everything is included!
			var copy = c.plainCopy().setStyle(cStyle);
			components.add(copy);
		});

		return components;
	}

	public static MutableComponent truncate(Component text, int max) {
		if(ChatFormatting.stripFormatting(text.getString()).length() <= max) {
			return (MutableComponent) text;
		}

		var truncated = Component.empty();
		int[] len = {0};

		text.visit((style, str) -> {
			if(str.length() + len[0] > max) {
				if(len[0] >= max) {
					return Optional.empty(); // if the text is the perfect length, don't add anything
				}

				str = str.substring(0, max - len[0]); // truncate the string to the max length
			}

			truncated.append(Component.literal(str).setStyle(style));

			len[0] += str.length();

			return Optional.empty();
		}, Style.EMPTY);

		return truncated;
	}

	/**
	 * Returns a copy of {@code text} with the specified {@code siblings}
	 * parameter replacing the original siblings. The passed {@code text}'s
	 * content and style are preserved.
	 */
	public static MutableComponent newSiblings(Component text, List<Component> siblings) {
		return new MutableComponent(text.getContents(), siblings, text.getStyle());
	}

	/**
	 * Returns a copy of {@code text} with an empty content.
	 * Useful for comparing {@link Component} objects'
	 * metadata (style and siblings) only.
	 * */
	/*public static MutableComponent withoutContent(Component text) {
		//-? if >1.20.2 {
		//import net.minecraft.network.chat.contents.PlainTextContents;
		//-?}
		return new MutableComponent(*//*-? if >1.20.2 {*//*PlainTextContents*//*-?} else {*//**//*ComponentContents*//**//*-?}*//*.EMPTY, text.getSiblings(), text.getStyle());
	}*/


	/**
	 * Formats a String with {@code &} formatting codes into a {@link Component}.
	 * First replaces all {@code &<?>} codes with a section symbol ({@code §}),
	 * then deletes the backslash from all {@code \&<?>} instances. Doesn't
	 * support hex colors.
	 *
	 * @apiNote Hex colors could be supported with the Placeholder API, but using
	 * an entire library just for this one feature seems excessive.
	 */
	public static MutableComponent text(String unformatted) {
		String s = NO_BACKSLASH_AMPERSAND_REGEX.reset(unformatted).replaceAll("§$1");
		s = AMPERSAND_REGEX.reset(s).replaceAll("&$2");

		return Component.literal(s);
	}


	/**
	 * @return {@code true} if `a` and `b` are virtually equal; that is, a
	 * styled color visitor produces the same sequence for both components.
	 * This is intended to compare the string and style data together, although
	 * this is a convoluted task that will always have edge cases and frustrating
	 * edge cases.
	 *
	 * todo note unsolvable issues here.
	 */
	public static <T> boolean virtuallyEqual(Component a, Component b) {
		// this is presumed true due to where this is called in tryCondenseDupes
		/*if(!a.getString().equals(b.getString())) {
			return false; // obviously the strings themselves need to be the same (case-insensitive tho..?)
		}*/

		Function<List<it.unimi.dsi.fastutil.Pair<String, Style>>, FormattedText.StyledContentConsumer<T>> visitMaker = list -> ((style, contents) -> {
			list.add(new ObjectObjectImmutablePair<>(
				contents.toLowerCase(Locale.ROOT),
				// forcibly clears any invisible style data - otherwise this will always fail due to the differing insertions from the timestamp
				style.withClickEvent(null).withHoverEvent(null).withInsertion(null)
			));
			return Optional.empty();
		});

		ObjectList<it.unimi.dsi.fastutil.Pair<String, Style>> aColors = new ObjectArrayList<>(), bColors = new ObjectArrayList<>();
		a.visit(visitMaker.apply(aColors), Style.EMPTY);
		b.visit(visitMaker.apply(bColors), Style.EMPTY);

		return aColors.equals(bColors);
	}

	/**
	 * Converts a {@link Component} into a {@link String} with {@code &<?>} codes.
	 * Strips any complex style data, including hover events, fonts, insertions,
	 * etc. Hex colors are represented in the format {@code &#RRGGBB}.
	 */
	public static String toLegacyString(Component text, boolean prettyPrint) { //todo: bruh make a freaking unit test for this are we serious.
		StringBuilder builder = new StringBuilder();
		AtomicReference<Style> lastStyle = new AtomicReference<>(Style.EMPTY); // ensures that the first equality check returns false

		// TEST:this with complex components (ex. root w/ hex color so &r applies that..?)
		for(Component part : text.toFlatList()) {
			part.visit((style, str) -> {
				if(str.isBlank()) {
					// add the whitespace but don't format anything
					builder.append(str);
					return Optional.empty();
				}
				//elif str.contains("&*") {str.replaceAll("&*", "\\\\&*")} //todo

				String leadingWhitespace = str.substring(0, str.indexOf(str.stripLeading()));

				// if style is different from last, add any formatting codes
				if(!style.equals(lastStyle.get())) {
					if(!leadingWhitespace.isEmpty()) {
						// adds the whitespace BEFORE the formatting codes and regular text to
						// provide better readability (hugs the right-most characters instead
						// of left-most)
						builder.append(leadingWhitespace);
					}

					builder.append(getFormattingCodes(style, lastStyle.get()));

					lastStyle.set(style);
				}

				// sometimes section signs leak and I WANT THEM OUT
				// this has to be done here or else the stylish color codes added above will be erased
				builder.append(str.stripLeading().replace(ChatFormatting.PREFIX_CODE, '&'));

				return Optional.empty();
			}, Style.EMPTY);
		}

		// === Output fixes and optimizations ===

		// removes any leading reset codes - all styles begin naturally reset
		while(builder.indexOf("&r") == 0) {
			builder.delete(0, 2);
		}

		// removes redundant white color codes directly succeeding reset codes (white is the default color already)
		for(int i = builder.indexOf("&r&f"); i != -1; i = builder.indexOf("&r&f")) {
			builder.delete(i + 2, i + 4);
		}

		String result = builder.toString();
		Matcher m;
		while((m = REDUNDANT_COLOR_REGEX.reset(result)).find()) {
			result = m.replaceFirst("$1&$2");
		}

		// finally, makes all formatting codes aqua so they're easily distinguishable
		if(prettyPrint && (m = PRETTY_PRINT_TARGETS_REGEX.reset(result)).find()) {
			result = m.replaceAll(ChatFormatting.AQUA + "$0" + ChatFormatting.RESET);
			// prepub: replace &<?> codes that were in the original message with \\\\$1 ? this regex alr ignores them
		}

		return result;
	}

	/**
	 * Returns the formatting codes of the {@link Style} provided, excluding any already
	 * applied ones according to {@code last}. Returns an empty string if the style is
	 * empty or blank. If any hex colors are specified, they will be returned in the
	 * format {@code &#RRGGBB}. Additionally, any color that exists as a formatting code
	 * (ex. {@code #55FF55} for {@link ChatFormatting#GREEN}) will return as the formatting
	 * code (ex. {@code &a}).
	 *
	 * @see TextColor#serialize()
	 */
	public static String getFormattingCodes(Style style, Style last) {
		StringJoiner joiner = new StringJoiner("&", "&", "").setEmptyValue(""); // adds the & at the start of the string

		//todo: optimizations for when either parameter is Style.EMPTY

		// if the color is named, it will have a name
		// makes the fallback white so changes to colorless but not empty styles don't ignore colors
		// see 'newBuff' in test cases under #311
		TextColor thisColor = Colors.simplify(style.getColor() != null ? style.getColor().getValue() : Colors.WHITE);
		int thisValue = thisColor.getValue();
		int lastValue = last.getColor() != null ? last.getColor().getValue() : Colors.WHITE;

		// ensures reset codes are not treated as white codes by forcing necessary reset codes
		boolean modifierChanged = (last.isBold() && !style.isBold()) || (last.isItalic() && !style.isItalic()) || (last.isUnderlined() && !style.isUnderlined()) ||
			(last.isStrikethrough() && !style.isStrikethrough()) || (last.isObfuscated() && !style.isObfuscated());

		if(modifierChanged) {
			// this is necessary because, unlike with colors, you cannot overwrite one modifier with another
			joiner.add("r");
		}

		// only add the color code if it's different from the last color and if it won't result in '&r&f'
		if(thisValue != lastValue && !(modifierChanged && thisValue == Colors.WHITE))
		{
			Optional<String> code = Colors.getCode(thisColor);
			// if thisColor is named, add its formatting code, else add its hex color
			joiner.add( code.orElse(thisColor.serialize()) );
		}
		else if(style.equals(Style.EMPTY) && !last.equals(Style.EMPTY))
		{
			return "&r"; // if the current style is empty but the last style wasn't, we've reset!
		}

		if(style.isBold() && !last.isBold()) joiner.add("l");
		if(style.isItalic() && !last.isItalic()) joiner.add("o");
		if(style.isUnderlined() && !last.isUnderlined()) joiner.add("n");
		if(style.isStrikethrough() && !last.isStrikethrough()) joiner.add("m");
		if(style.isObfuscated() && !last.isObfuscated()) joiner.add("k");

		return joiner.toString();
	}
}